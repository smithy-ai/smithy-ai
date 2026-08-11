package dev.smithyai.orchestrator.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.model.IssueContext;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.actions.*;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionParser;
import dev.smithyai.orchestrator.runtime.engine.ExpressionRenderer;
import dev.smithyai.orchestrator.runtime.engine.ForeachAction;
import dev.smithyai.orchestrator.runtime.engine.StepExecutor;
import dev.smithyai.orchestrator.runtime.store.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The primitives a cross-repo coordinator is built from.
 *
 * <p>The foreman did all of this by hand inside one 1033-line class: it kept its
 * children in a JSON file inside its own container, encoded parentage as a
 * "Parent story:" marker in each child issue's body, and rebuilt an in-memory
 * index by reading containers. Here it is fan-out over a list, ordinary issues
 * in the target repositories, and parentage in the run store.
 */
class FanOutTest {

    private static final RepoInfo REPO = new RepoInfo("acme", "platform", "https://git.invalid/acme/platform");

    /** Fan a planned list of work items out into child runs. */
    private static final String COORDINATOR = """
        apiVersion: smithy.ai/v1alpha1
        kind: Workflow
        metadata:
          name: feature-coordinator
        state:
          initial: planning
          planning:
            on:
              issue.plan_approved:
                to: executing
                steps:
                  - uses: foreach
                    id: fanout
                    with:
                      items: "{{ vars.plan }}"
                    steps:
                      - uses: issue.create
                        id: issue
                        with:
                          repo: "{{ item }}"
                      - uses: run.spawn
                        id: child
                        with:
                          workflow: smithy-development
                          state: refine
                          repo: "{{ item }}"
                      - uses: correlate
                        with:
                          kind: issue
                          ref: "{{ item }}#{{ steps.issue.number }}"
                          run: "{{ steps.child.runId }}"
          executing:
            on: {}
        """;

    @TempDir
    Path tempDir;

    private final List<String> createdIssues = new ArrayList<>();
    private RunStore store;
    private StepExecutor executor;

    @BeforeEach
    void setUp() {
        createdIssues.clear();

        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("runs.db") + "?foreign_keys=on");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        store = new RunStore(JdbcClient.create((DataSource) dataSource), new ObjectMapper());

        // Stands in for creating an ordinary VCS issue in the target repository.
        WorkflowAction createIssue = new WorkflowAction() {
            @Override
            public String type() {
                return "issue.create";
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                createdIssues.add(String.valueOf(input.get("repo")));
                return Map.of("number", createdIssues.size());
            }
        };

        var renderer = new ExpressionRenderer();
        var foreach = new ForeachAction(null);
        var registry = new ActionRegistry(
            List.of(
                createIssue,
                new RunSpawnAction(store),
                new CorrelateAction(store),
                new RunAwaitAction(store),
                foreach
            )
        );
        executor = new StepExecutor(registry, renderer, store);
        // The foreach action drives the executor; wire the cycle by hand here.
        setExecutor(foreach, executor);
    }

    private static void setExecutor(ForeachAction foreach, StepExecutor executor) {
        try {
            var field = ForeachAction.class.getDeclaredField("executor");
            field.setAccessible(true);
            field.set(foreach, executor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static WorkflowEvent approved() {
        return new WorkflowEvent.PlanApproved(new IssueContext(REPO, "ECD-4309", "A feature", "body", "main"), "alice");
    }

    private Run coordinatorWithPlan(List<String> repos) {
        var run = store.create("feature-coordinator", null, "planning", null);
        store.updateVars(run.id(), Map.of("plan", repos));
        return store.find(run.id()).orElseThrow();
    }

    @Test
    void fansOutOneChildRunPerPlannedItem() {
        var definition = new WorkflowDefinitionParser().parse("coordinator.yml", COORDINATOR);
        var run = coordinatorWithPlan(List.of("acme/api", "acme/web", "acme/worker"));
        var steps = definition.state().getStages().get("planning").on().get("issue.plan_approved").steps();

        executor.execute(run, approved(), "planning:issue.plan_approved", steps);

        // An ordinary issue in each target repository — no tracker sub-issues.
        assertEquals(List.of("acme/api", "acme/web", "acme/worker"), createdIssues);

        var children = store.findChildren(run.id());
        assertEquals(3, children.size());
        assertTrue(children.stream().allMatch(c -> c.workflowName().equals("smithy-development")));
        assertTrue(children.stream().allMatch(c -> c.rootRunId().equals(run.id())), "children share the story's root");
    }

    @Test
    void eachChildCarriesItsOwnTaskAsVariables() {
        var definition = new WorkflowDefinitionParser().parse("coordinator.yml", COORDINATOR);
        var run = coordinatorWithPlan(List.of("acme/api", "acme/web"));
        var steps = definition.state().getStages().get("planning").on().get("issue.plan_approved").steps();

        executor.execute(run, approved(), "planning:issue.plan_approved", steps);

        var repos = store
            .findChildren(run.id())
            .stream()
            .map(child -> child.vars().get("repo"))
            .toList();
        assertEquals(List.of("acme/api", "acme/web"), repos);
    }

    @Test
    void aChildIssueRoutesBackToItsChildRunWithoutAMarkerInTheIssueBody() {
        var definition = new WorkflowDefinitionParser().parse("coordinator.yml", COORDINATOR);
        var run = coordinatorWithPlan(List.of("acme/api", "acme/web"));
        var steps = definition.state().getStages().get("planning").on().get("issue.plan_approved").steps();

        executor.execute(run, approved(), "planning:issue.plan_approved", steps);

        var owner = store.findByCorrelation(CorrelationKind.ISSUE, "acme/web#2").orElseThrow();
        assertEquals("smithy-development", owner.workflowName());
        assertEquals(run.id(), owner.parentRunId(), "and the parent is reachable from it");
    }

    @Test
    void awaitReportsProgressAndOnlySatisfiesWhenChildrenFinish() {
        var definition = new WorkflowDefinitionParser().parse("coordinator.yml", COORDINATOR);
        var run = coordinatorWithPlan(List.of("acme/api", "acme/web"));
        var steps = definition.state().getStages().get("planning").on().get("issue.plan_approved").steps();
        executor.execute(run, approved(), "planning:issue.plan_approved", steps);

        var await = new RunAwaitAction(store);
        var context = new ActionContext(run, approved(), Map.of(), run.vars());

        var pending = await.execute(context, Map.of());
        assertEquals(false, pending.get("satisfied"));
        assertEquals(2L, pending.get("pending"));

        store.findChildren(run.id()).forEach(child -> store.updateStatus(child.id(), RunStatus.COMPLETED));

        var done = await.execute(context, Map.of());
        assertEquals(true, done.get("satisfied"));
        assertEquals(0L, done.get("pending"));
    }

    @Test
    void aWaveCanReleaseBeforeTheWholeFanOutFinishes() {
        var definition = new WorkflowDefinitionParser().parse("coordinator.yml", COORDINATOR);
        var run = coordinatorWithPlan(List.of("acme/api", "acme/web", "acme/worker"));
        var steps = definition.state().getStages().get("planning").on().get("issue.plan_approved").steps();
        executor.execute(run, approved(), "planning:issue.plan_approved", steps);

        var await = new RunAwaitAction(store);
        var context = new ActionContext(run, approved(), Map.of(), run.vars());
        store.updateStatus(store.findChildren(run.id()).getFirst().id(), RunStatus.COMPLETED);

        assertEquals(true, await.execute(context, Map.of("count", 1)).get("satisfied"));
        assertEquals(false, await.execute(context, Map.of("count", 2)).get("satisfied"));
    }

    @Test
    void replayingTheFanOutDoesNotCreateDuplicateIssuesOrRuns() {
        var definition = new WorkflowDefinitionParser().parse("coordinator.yml", COORDINATOR);
        var run = coordinatorWithPlan(List.of("acme/api", "acme/web"));
        var steps = definition.state().getStages().get("planning").on().get("issue.plan_approved").steps();

        executor.execute(run, approved(), "planning:issue.plan_approved", steps);
        // The orchestrator restarts mid-fan-out and replays the transition.
        executor.execute(run, approved(), "planning:issue.plan_approved", steps);

        assertEquals(List.of("acme/api", "acme/web"), createdIssues, "issues are not created twice");
        assertEquals(2, store.findChildren(run.id()).size(), "and neither are child runs");
    }
}
