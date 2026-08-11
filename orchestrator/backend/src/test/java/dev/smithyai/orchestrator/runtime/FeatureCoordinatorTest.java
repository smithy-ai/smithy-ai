package dev.smithyai.orchestrator.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.WorkflowPolicyConfig;
import dev.smithyai.orchestrator.model.IssueContext;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.actions.*;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionLoader;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionParser;
import dev.smithyai.orchestrator.runtime.engine.*;
import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.runtime.store.*;
import dev.smithyai.orchestrator.testing.StubVcsClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * The built-in cross-repository coordinator, running as a definition.
 *
 * <p>This is the workflow the whole re-architecture was for. Its behaviour
 * reference is the 1033-line hardcoded flow at {@code parked/foreman-reference};
 * what these tests assert is that the parts that were hard-won there survive —
 * fan-out, dependency-ordered waves, and a parent/child link that needs nothing
 * from the issue tracker.
 *
 * <p>The agent and the container are stubbed: what is under test is the
 * definition and the engine, not Claude.
 */
class FeatureCoordinatorTest {

    private static final RepoInfo STORY_REPO = new RepoInfo("acme", "product", "https://git.invalid/acme/product");

    /** An operator's catalog, supplied the documented way: by extending the built-in. */
    private static final String ACME_CATALOG = """
        apiVersion: smithy.ai/v1alpha1
        kind: Workflow
        metadata:
          name: acme-coordinator
          extends: feature-coordinator
        vars:
          catalog:
            - owner: acme
              repo: api
              description: The HTTP API
            - owner: acme
              repo: web
              description: The web client
          botUser: acme-bot
        """;

    @TempDir
    Path tempDir;

    private RunStore store;
    private RunEngine engine;
    private StubVcsClient vcs;
    private final List<Map<String, Object>> assignments = new ArrayList<>();

    /** What the planning agent came back with. */
    private Map<String, Object> plannedIssues = Map.of();

    @BeforeEach
    void setUp() throws Exception {
        assignments.clear();
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("runs.db") + "?foreign_keys=on");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        store = new RunStore(JdbcClient.create((DataSource) dataSource), new ObjectMapper());
        vcs = new StubVcsClient();

        var definitions = Files.createDirectory(tempDir.resolve("workflows"));
        Files.writeString(definitions.resolve("acme-coordinator.yml"), ACME_CATALOG);

        plannedIssues = Map.of(
            "summary",
            "Add search across the API and the web client.",
            "issues",
            List.of(
                Map.of(
                    "owner",
                    "acme",
                    "repo",
                    "api",
                    "title",
                    "Search endpoint",
                    "body",
                    "GET /search",
                    "dependsOn",
                    List.of()
                ),
                Map.of(
                    "owner",
                    "acme",
                    "repo",
                    "web",
                    "title",
                    "Search box",
                    "body",
                    "Calls GET /search",
                    "dependsOn",
                    List.of(0)
                )
            )
        );

        var renderer = new ExpressionRenderer();
        var stateActions = new StateActions();
        var issueActions = new IssueActions();
        var foreach = new ForeachAction(null);

        var actions = new ActionRegistry(
            List.of(
                foreach,
                new CorrelateAction(store),
                new RunSpawnAction(store),
                new RunAwaitAction(store),
                new RunWaveAction(store),
                new GateAwaitAction(store),
                new SignalEmitAction(store, this::deliverSignal),
                new IssueCommentAction(vcs),
                issueActions.issueCreateAction(vcs),
                recordingAssign(issueActions.issueAssignAction(vcs)),
                stateActions.stateSetAction(store),
                stateActions.stateVarAction(store),
                stateActions.metricsRecordAction(store),
                stubContainerInit(),
                stubPlanningAgent()
            )
        );

        var executor = new StepExecutor(actions, renderer, store);
        setExecutor(foreach, executor);

        var policy = new WorkflowPolicyConfig(null, null, definitions.toString(), true);
        var workflows = new WorkflowRegistry(
            new WorkflowDefinitionLoader(new WorkflowDefinitionParser()),
            new CapabilityValidator(actions),
            policy,
            vcs,
            vcs
        );
        workflows.loadAll();

        engine = new RunEngine(
            workflows,
            new WorkflowRouter(renderer),
            executor,
            store,
            new RunEnvironments(store, null, null),
            null
        );
    }

    // ── Stubs ────────────────────────────────────────────────

    private boolean deliverSignal(String targetRunId, WorkflowEvent.Signal signal) {
        return engine.deliver(targetRunId, signal);
    }

    /** The coordinator's workspace container, minus Docker. */
    private WorkflowAction stubContainerInit() {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "container.init";
            }

            @Override
            public java.util.Set<Capability> requires() {
                return java.util.Set.of(Capability.ENVIRONMENT);
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                return Map.of("name", required(input, "name"), "created", true);
            }
        };
    }

    private WorkflowAction stubPlanningAgent() {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "agent.runStructured";
            }

            @Override
            public java.util.Set<Capability> requires() {
                return java.util.Set.of(Capability.ENVIRONMENT, Capability.AGENT);
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                // The catalog reaches the prompt, which is the whole point of
                // making it configuration rather than a constant in Java.
                assertInstanceOf(Map.class, input.get("vars"));
                return plannedIssues;
            }
        };
    }

    private WorkflowAction recordingAssign(WorkflowAction delegate) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return delegate.type();
            }

            @Override
            public java.util.Set<Capability> requires() {
                return delegate.requires();
            }

            @Override
            public boolean idempotent() {
                return delegate.idempotent();
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                assignments.add(new LinkedHashMap<>(input));
                return delegate.execute(context, input);
            }
        };
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

    // ── Driving the story ────────────────────────────────────

    private static WorkflowEvent storyAssigned() {
        return new WorkflowEvent.IssueAssigned(
            new IssueContext(STORY_REPO, "PROD-1", "Search everywhere", "Users want search", "main"),
            "https://git.invalid/acme/product"
        );
    }

    private static WorkflowEvent storyApproved() {
        return new WorkflowEvent.PlanApproved(
            new IssueContext(STORY_REPO, "PROD-1", "Search everywhere", "Users want search", "main"),
            "alice"
        );
    }

    private Run story() {
        return store.findByCorrelation(CorrelationKind.KEY, "acme-coordinator|story:acme/product#PROD-1").orElseThrow();
    }

    private void finish(Run child) {
        store.updateStatus(child.id(), RunStatus.COMPLETED);
        var signal = new SignalEmitAction(store, this::deliverSignal);
        signal.execute(
            new ActionContext(store.find(child.id()).orElseThrow(), storyApproved(), Map.of(), child.vars()),
            Map.of("signal", "child-done")
        );
    }

    // ── Tests ────────────────────────────────────────────────

    @Test
    void planningPostsThePlanAndWaitsForAHuman() {
        engine.handle(storyAssigned());

        var run = story();
        assertEquals("awaiting_approval", run.state());
        assertEquals(List.of("Add search across the API and the web client."), vcs.issueComments);
        assertEquals(1, store.findPendingWaits(run.id()).size(), "and nothing else happens until approval");
        assertTrue(vcs.createdIssues.isEmpty(), "no issue is created before a human agrees");
    }

    @Test
    void approvalFansOutOneOrdinaryIssuePerRepository() {
        engine.handle(storyAssigned());
        engine.handle(storyApproved());

        assertEquals(
            List.of("acme/api", "acme/web"),
            vcs.createdIssues
                .stream()
                .map(issue -> issue.owner() + "/" + issue.repo())
                .toList()
        );
        assertEquals(2, store.findChildren(story().id()).size());
        assertEquals("executing", story().state());
    }

    @Test
    void aChildIssueRoutesToItsRunWithNothingWrittenIntoTheIssueBody() {
        engine.handle(storyAssigned());
        engine.handle(storyApproved());

        var created = vcs.createdIssues.getFirst();
        assertFalse(created.body().contains("Parent story"), "the body is what the agent wrote, nothing else");

        var child = store.findByCorrelation(CorrelationKind.ISSUE, "acme/api#" + created.issueRef()).orElseThrow();
        assertEquals("smithy-development", child.workflowName());
        assertEquals(story().id(), child.parentRunId());
    }

    @Test
    void onlyTheFirstWaveIsAssigned() {
        engine.handle(storyAssigned());
        engine.handle(storyApproved());

        // The web client depends on the API, so it is created but not started.
        assertEquals(1, assignments.size());
        assertEquals("api", assignments.getFirst().get("repo"));
        assertEquals("acme-bot", assignments.getFirst().get("assignees"), "the operator's bot, from their catalog");
    }

    @Test
    void aChildFinishingReleasesTheWaveThatDependedOnIt() {
        engine.handle(storyAssigned());
        engine.handle(storyApproved());

        var api = store
            .findChildren(story().id())
            .stream()
            .filter(child -> "api".equals(child.vars().get("repo")))
            .findFirst()
            .orElseThrow();
        finish(api);

        assertEquals(2, assignments.size(), "the second wave went out");
        assertEquals("web", assignments.get(1).get("repo"));
    }

    @Test
    void theStoryCompletesOnlyWhenEveryChildHas() {
        engine.handle(storyAssigned());
        engine.handle(storyApproved());
        var children = store.findChildren(story().id());

        finish(children.getFirst());
        assertEquals("executing", story().state(), "one child down is not done");

        finish(children.get(1));
        assertEquals("done", story().state());
        assertEquals(RunStatus.COMPLETED, story().status());
    }

    @Test
    void replayingTheFanOutCreatesNothingTwice() {
        engine.handle(storyAssigned());
        engine.handle(storyApproved());
        // The orchestrator restarts and the approval is redelivered.
        store.updateState(story().id(), "awaiting_approval");
        engine.handle(storyApproved());

        assertEquals(2, vcs.createdIssues.size(), "issues are not duplicated");
        assertEquals(2, store.findChildren(story().id()).size(), "and neither are child runs");
    }
}
