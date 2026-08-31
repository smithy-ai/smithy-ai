package dev.smithyai.orchestrator.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.WorkflowPolicyConfig;
import dev.smithyai.orchestrator.model.IssueContext;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.actions.*;
import dev.smithyai.orchestrator.runtime.definition.RepositoryWorkflowLoader;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionLoader;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionParser;
import dev.smithyai.orchestrator.runtime.engine.*;
import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import dev.smithyai.orchestrator.testing.StubVcsClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A repository carrying its own workflow.
 *
 * <p>A team that wants its own flow should not need a file on the
 * orchestrator's disk, any more than they need one to have CI.
 */
class RepositoryWorkflowTest {

    private static final RepoInfo REPO = new RepoInfo("acme", "app", "https://git.invalid/acme/app");

    private static final String OWN_WORKFLOW = """
        apiVersion: smithy.ai/v1alpha1
        kind: Workflow
        metadata:
          name: acme-greeter
        routing:
          - event: issue.assigned
            action: create
            key: "{{ repo.fullName }}#{{ event.issueRef }}"
        state:
          initial: new
          new:
            on:
              issue.assigned:
                steps:
                  - uses: state.var
                    with:
                      greeted: true
        """;

    /** Needs a file-delete API, which not every provider has. */
    private static final String NEEDS_TOO_MUCH = """
        apiVersion: smithy.ai/v1alpha1
        kind: Workflow
        metadata:
          name: acme-tidier
        routing:
          - event: issue.assigned
            action: create
            key: "tidy:{{ event.issueRef }}"
        state:
          initial: new
          new:
            on:
              issue.assigned:
                steps:
                  - uses: not.an.action
        """;

    @TempDir
    Path tempDir;

    private RunStore store;
    private RunEngine engine;
    private StubVcsClient vcs;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("runs.db") + "?foreign_keys=on");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        store = new RunStore(JdbcClient.create((DataSource) dataSource), new ObjectMapper());
        vcs = new StubVcsClient();

        var renderer = new ExpressionRenderer();
        var state = new StateActions();
        var actions = new ActionRegistry(List.of(state.stateVarAction(store), state.stateSetAction(store)));

        // An empty directory: everything here comes from the repository itself.
        var definitions = Files.createDirectory(tempDir.resolve("workflows"));
        var policy = new WorkflowPolicyConfig(null, null, definitions.toString());
        var parser = new WorkflowDefinitionParser();
        var registry = new WorkflowRegistry(
            new WorkflowDefinitionLoader(parser),
            new CapabilityValidator(actions),
            policy,
            vcs,
            vcs,
            vcs
        );
        registry.loadAll();

        engine = new RunEngine(
            registry,
            new WorkflowRouter(renderer),
            new StepExecutor(actions, renderer, store),
            store,
            new RunEnvironments(store, null, null),
            new RepositoryWorkflowLoader(vcs, parser),
            new EventDebouncer(),
            new RunLocks()
        );
    }

    private static WorkflowEvent assigned() {
        return new WorkflowEvent.IssueAssigned(new IssueContext(REPO, "APP-3", "Do a thing", "body", "main"), null);
    }

    @Test
    void aRepositoryCanBringItsOwnWorkflow() {
        vcs.repositoryFiles.put("acme/app:.smithy/workflows/greeter.yml", OWN_WORKFLOW);

        var outcomes = engine.handle(assigned());

        assertEquals(1, outcomes.size());
        assertTrue(outcomes.getFirst().handled());
        assertEquals("acme-greeter", outcomes.getFirst().workflowName());
        assertEquals(true, store.find(outcomes.getFirst().runId()).orElseThrow().vars().get("greeted"));
    }

    @Test
    void aTrackerScopeWithNoRepositoryBehindItIsNeverLookedUp() {
        // A Jira story without a repository field is scoped as PROJECT/PROJECT
        // with no clone URL. There is nothing to list for it.
        var neverCalled = new StubVcsClient() {
            @Override
            public List<String> listRepositoryFiles(String owner, String repo, String path, String ref) {
                throw new AssertionError("a scope with no repository behind it must not be listed");
            }
        };
        var loader = new RepositoryWorkflowLoader(neverCalled, new WorkflowDefinitionParser());

        assertEquals(List.of(), loader.forRepository(new RepoInfo("YSIS", "YSIS", null, "jira")));
    }

    @Test
    void aRepositoryWithNoWorkflowsOfItsOwnIsUnaffected() {
        assertTrue(engine.handle(assigned()).isEmpty());
        assertTrue(store.findRecent(10).isEmpty());
    }

    @Test
    void aWorkflowThisOrchestratorCannotRunIsDroppedNotAttempted() {
        vcs.repositoryFiles.put("acme/app:.smithy/workflows/greeter.yml", OWN_WORKFLOW);
        vcs.repositoryFiles.put("acme/app:.smithy/workflows/tidier.yml", NEEDS_TOO_MUCH);

        var outcomes = engine.handle(assigned());

        assertEquals(List.of("acme-greeter"), outcomes.stream().map(RunEngine.Outcome::workflowName).toList());
    }

    @Test
    void anUnparseableDefinitionCostsThatWorkflowAndNothingElse() {
        vcs.repositoryFiles.put("acme/app:.smithy/workflows/greeter.yml", OWN_WORKFLOW);
        vcs.repositoryFiles.put("acme/app:.smithy/workflows/broken.yml", "this: is: not: a workflow");

        var outcomes = engine.handle(assigned());

        assertEquals(1, outcomes.size(), "the readable one still runs");
        assertTrue(outcomes.getFirst().handled());
    }

    @Test
    void aRepositorysOwnDefinitionOverridesTheOneItSharesANameWith() {
        // The docs promise repository definitions have the highest precedence.
        // Appending them to the global list meant the global one won instead.
        vcs.repositoryFiles.put(
            "acme/app:.smithy/workflows/greeter.yml",
            OWN_WORKFLOW.replace("acme-greeter", "smithy-development")
        );

        var outcomes = engine.handle(assigned()).stream().filter(RunEngine.Outcome::handled).toList();

        assertEquals(1, outcomes.size(), outcomes.toString());
        assertEquals("smithy-development", outcomes.getFirst().workflowName());
        assertEquals(
            true,
            store.find(outcomes.getFirst().runId()).orElseThrow().vars().get("greeted"),
            "the repository's version ran, not the built-in"
        );
    }
}
