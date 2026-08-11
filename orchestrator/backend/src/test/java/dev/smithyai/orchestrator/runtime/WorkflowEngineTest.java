package dev.smithyai.orchestrator.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.model.IssueContext;
import dev.smithyai.orchestrator.model.PrContext;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.actions.*;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionException;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionParser;
import dev.smithyai.orchestrator.runtime.definition.WorkflowRoutingAction;
import dev.smithyai.orchestrator.runtime.engine.*;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Runs a real YAML definition through routing and execution against the store —
 * the pieces connected, not just individually correct.
 */
class WorkflowEngineTest {

    private static final RepoInfo REPO = new RepoInfo("acme", "app", "https://git.invalid/acme/app");

    private static final String WORKFLOW = """
        apiVersion: smithy.ai/v1alpha1
        kind: Workflow
        metadata:
          name: smithy-development
        routing:
          - event: issue.assigned
            action: create
            key: "smithy.{{ repo.owner }}.{{ repo.name }}.{{ event.issueRef }}"
          - event: pr.merged
            action: ignore
            key: "unused"
        state:
          initial: refine
          terminal: done
          refine:
            on:
              issue.assigned:
                to: build
                steps:
                  - uses: issue.comment
                    id: announce
                    with:
                      body: "Working on {{ event.issueTitle }}"
          build:
            on: {}
          done:
            on: {}
        """;

    /** A second workflow listening to the same event, told apart by its predicate. */
    private static final String OTHER_WORKFLOW = """
        apiVersion: smithy.ai/v1alpha1
        kind: Workflow
        metadata:
          name: docs-only
        routing:
          - event: issue.assigned
            when: "{{ repo.name == 'docs' }}"
            action: create
            key: "docs.{{ event.issueRef }}"
        state:
          initial: start
          start:
            on: {}
        """;

    @TempDir
    Path tempDir;

    private final WorkflowDefinitionParser parser = new WorkflowDefinitionParser();
    private final ExpressionRenderer renderer = new ExpressionRenderer();
    private final WorkflowRouter router = new WorkflowRouter(renderer);

    private final List<String> posted = new ArrayList<>();
    private ActionRegistry registry;
    private RunStore store;
    private StepExecutor executor;

    @BeforeEach
    void setUp() {
        posted.clear();
        WorkflowAction comment = new WorkflowAction() {
            @Override
            public String type() {
                return "issue.comment";
            }

            @Override
            public Set<Capability> requires() {
                return Set.of(Capability.ISSUE_COMMENT);
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                posted.add(String.valueOf(input.get("body")));
                return Map.of("commentId", posted.size());
            }
        };
        registry = new ActionRegistry(List.of(comment));

        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("runs.db") + "?foreign_keys=on");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        store = new RunStore(JdbcClient.create((DataSource) dataSource), new ObjectMapper());
        executor = new StepExecutor(registry, renderer, store);
    }

    private static WorkflowEvent issueAssigned(String repo) {
        var info = new RepoInfo("acme", repo, "https://git.invalid/acme/" + repo);
        return new WorkflowEvent.IssueAssigned(
            new IssueContext(info, "7", "Add a thing", "body", "main"),
            "https://git.invalid/acme/" + repo
        );
    }

    @Test
    void routesAnEventToACreateDecisionWithARenderedKey() {
        var definition = parser.parse("smithy.yml", WORKFLOW);

        var decisions = router.route(issueAssigned("app"), List.of(definition));

        assertEquals(1, decisions.size());
        var decision = decisions.getFirst();
        assertEquals("smithy-development", decision.workflowName());
        assertEquals(WorkflowRoutingAction.create, decision.action());
        assertEquals("smithy.acme.app.7", decision.key());
    }

    @Test
    void anIgnoreRuleProducesNoDecision() {
        var definition = parser.parse("smithy.yml", WORKFLOW);
        var merged = new WorkflowEvent.PrMerged(new PrContext(REPO, 42, "t", "b", true, "smithy/7-x", "main"));

        assertEquals(List.of(), router.route(merged, List.of(definition)));
    }

    @Test
    void anUnmatchedEventProducesNoDecision() {
        var definition = parser.parse("smithy.yml", WORKFLOW);
        var unassigned = new WorkflowEvent.IssueUnassigned(new IssueContext(REPO, "7", "t", "b", "main"));

        assertEquals(List.of(), router.route(unassigned, List.of(definition)));
    }

    @Test
    void predicatesTellApartTwoWorkflowsListeningToTheSameEvent() {
        var definitions = List.of(parser.parse("smithy.yml", WORKFLOW), parser.parse("docs.yml", OTHER_WORKFLOW));

        // In acme/app only the general workflow matches.
        var appNames = router
            .route(issueAssigned("app"), definitions)
            .stream()
            .map(WorkflowRouter.Decision::workflowName)
            .toList();
        assertEquals(List.of("smithy-development"), appNames);

        // In acme/docs both do — the predicate is what distinguishes them, and
        // routing on the event name alone could not.
        var docsNames = router
            .route(issueAssigned("docs"), definitions)
            .stream()
            .map(WorkflowRouter.Decision::workflowName)
            .toList();
        assertEquals(List.of("smithy-development", "docs-only"), docsNames);
    }

    @Test
    void executesTheRoutedTransitionAgainstARealRun() {
        var definition = parser.parse("smithy.yml", WORKFLOW);
        var event = issueAssigned("app");

        var decision = router.route(event, List.of(definition)).getFirst();
        var run = store.create(decision.workflowName(), null, definition.state().getInitial(), null);

        var transition = definition.state().getStages().get(run.state()).on().get(event.name());
        var outputs = executor.execute(
            run,
            event,
            StepExecutor.transitionId(run.state(), event.name()),
            transition.steps()
        );
        store.updateState(run.id(), transition.to());

        assertEquals(List.of("Working on Add a thing"), posted);
        assertEquals(1, outputs.get("announce").get("commentId"));
        assertEquals("build", store.find(run.id()).orElseThrow().state(), "the run advanced to the target state");
    }

    @Test
    void aDefinitionNeedingAnUnsupportedCapabilityIsRejectedAtLoad() {
        var validator = new CapabilityValidator(registry);
        var definition = parser.parse("smithy.yml", WORKFLOW);

        assertDoesNotThrow(() -> validator.validate("smithy.yml", definition, Set.of(Capability.ISSUE_COMMENT)));

        var error = assertThrows(WorkflowDefinitionException.class, () ->
            validator.validate("smithy.yml", definition, Set.of())
        );
        assertTrue(error.getMessage().contains("issue.comment"), error.getMessage());
        assertTrue(error.getMessage().contains("does not support"), error.getMessage());
    }

    @Test
    void aDefinitionNamingAnUnknownActionIsRejectedAtLoad() {
        var validator = new CapabilityValidator(registry);
        var definition = parser.parse("broken.yml", WORKFLOW.replace("uses: issue.comment", "uses: does.not.exist"));

        var error = assertThrows(WorkflowDefinitionException.class, () ->
            validator.validate("broken.yml", definition, Set.of(Capability.ISSUE_COMMENT))
        );
        assertTrue(error.getMessage().contains("does.not.exist"), error.getMessage());
    }
}
