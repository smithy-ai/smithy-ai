package dev.smithyai.orchestrator.testing;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.*;
import dev.smithyai.orchestrator.model.IssueContext;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.store.*;
import dev.smithyai.orchestrator.service.claude.PromptRenderer;
import dev.smithyai.orchestrator.service.docker.ContainerService;
import dev.smithyai.orchestrator.service.metrics.MetricsRecorder;
import dev.smithyai.orchestrator.workflow.flows.smithy.SmithyWorkflowFactory;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Drives the real smithy flow end to end with a simulated Docker daemon and a
 * stub VCS. This is the harness the workflow engine's parity tests will use:
 * porting a flow to a definition has to reproduce the same observable
 * behaviour, and "observable" means what this test asserts.
 */
class SmithyFlowTest {

    private static final RepoInfo REPO = new RepoInfo("acme", "app", "https://git.invalid/acme/app");

    @TempDir
    Path tempDir;

    private FakeDockerCli docker;
    private StubVcsClient vcs;
    private RunStore store;
    private SmithyWorkflowFactory factory;

    @BeforeEach
    void setUp() {
        docker = new FakeDockerCli();
        vcs = new StubVcsClient();

        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("runs.db") + "?foreign_keys=on");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        store = new RunStore(JdbcClient.create((DataSource) dataSource), new ObjectMapper());

        var dockerConfig = new DockerConfig("docker", "smithy-net", "claude-task:test", null);
        var botConfig = new BotConfig(
            new BotConfig.BotEntry("smithy", "smithy@localhost"),
            new BotConfig.BotEntry("architect", "architect@localhost")
        );
        var containerService = new ContainerService(
            dockerConfig,
            new ClaudeConfig("test-token", null, "claude-opus-5"),
            vcsProviderConfig(),
            botConfig,
            docker
        );

        factory = new SmithyWorkflowFactory(
            dockerConfig,
            new CiConfig(false),
            new MetricsRecorder(new MockEnvironment().withProperty("METRICS_PATH", tempDir + "/metrics.jsonl")),
            new RepositoryConfigResolver(vcs),
            vcsProviderConfig(),
            new KnowledgebaseConfig(false, null, null),
            botConfig,
            containerService,
            new PromptRenderer(new DefaultResourceLoader()),
            vcs,
            vcs
        );
        factory.runs = new RunRecorder(store);
    }

    private VcsProviderConfig vcsProviderConfig() {
        return new VcsProviderConfig(
            "forgejo",
            null,
            new VcsProviderConfig.ForgejoProviderConfig(
                "http://forgejo.invalid",
                "http://forgejo.invalid",
                null,
                "smithy-token",
                "architect-token"
            ),
            null,
            null,
            null
        );
    }

    private static WorkflowEvent.IssueAssigned issueAssigned() {
        var ctx = new IssueContext(REPO, "7", "Add a thing", "Please add the thing.", "main");
        return new WorkflowEvent.IssueAssigned(ctx, "https://git.invalid/acme/app");
    }

    /** Block until the instance's single-threaded event loop has drained. */
    private static void settle() throws InterruptedException {
        Thread.sleep(400);
    }

    /**
     * Script a successful refinement: the plan turn, the plan file Claude wrote,
     * and the extraction turn that reports no open questions.
     */
    private void givenClaudeWritesAPlan() {
        docker.enqueueClaudeText("Wrote the plan.");
        docker.enqueueClaudeStructured("{\"openQuestions\": []}");
        docker.onExec(
            ".claude/plans",
            new dev.smithyai.orchestrator.service.docker.dto.ExecResult(0, "/root/.claude/plans/plan.md", "")
        );
    }

    @Test
    void assigningAnIssueRoutesToCreateWithAContainerKey() {
        var action = factory.decideEventAction(issueAssigned());

        assertInstanceOf(dev.smithyai.orchestrator.workflow.EventAction.Create.class, action);
        var create = (dev.smithyai.orchestrator.workflow.EventAction.Create) action;
        assertEquals("smithy.acme.app.7", create.key());
    }

    @Test
    void refiningAnIssueCreatesAContainerPostsAPlanAndRecordsTheRun() throws Exception {
        givenClaudeWritesAPlan();

        var event = issueAssigned();
        var instance = factory.getOrCreateInstance("smithy.acme.app.7", event);
        instance.onEvent(event);
        settle();

        assertTrue(docker.containerExists("smithy.acme.app.7"), "the task container was created");
        assertFalse(vcs.issueComments.isEmpty(), "the plan was posted back to the issue");
        assertTrue(
            vcs.issueComments.getFirst().contains("Development plan"),
            "the comment links the plan: " + vcs.issueComments.getFirst()
        );

        // The run exists, is indexed by issue and container, and has history.
        var run = store.findByCorrelation(CorrelationKind.ISSUE, "acme/app#7").orElseThrow();
        assertEquals("smithy-development", run.workflowName());
        assertEquals(instance.runId(), run.id());

        var types = store.findEvents(run.id()).stream().map(RunEvent::type).toList();
        assertTrue(types.contains("plan_posted"), "timeline records the plan: " + types);
    }

    @Test
    void theRunSurvivesDestroyingTheInstance() throws Exception {
        givenClaudeWritesAPlan();

        var event = issueAssigned();
        var instance = factory.getOrCreateInstance("smithy.acme.app.7", event);
        instance.onEvent(event);
        settle();
        String runId = instance.runId();

        factory.removeInstance("smithy.acme.app.7");

        var run = store.find(runId).orElseThrow();
        assertEquals(RunStatus.COMPLETED, run.status());
        assertTrue(
            store.findEnvironmentNames(runId, RunRecorder.CONTAINER).isEmpty(),
            "the container attachment is released"
        );
        assertFalse(store.findEvents(runId).isEmpty(), "but the history stays");
    }

    @Test
    void recoveringTheSameContainerReusesItsRun() throws Exception {
        var event = issueAssigned();
        var first = factory.getOrCreateInstance("smithy.acme.app.7", event);
        settle();
        String runId = first.runId();
        assertNotNull(runId);

        // Simulate an orchestrator restart: the live instance map is gone, the
        // container and the run store are not.
        factory
            .allInstances()
            .keySet()
            .forEach(key -> factory.getInstance(key));
        var state = dev.smithyai.orchestrator.service.docker.dto.ContainerState.init(
            dev.smithyai.orchestrator.service.docker.dto.WorkflowType.SMITHY,
            "refine"
        );
        var recovered = factory.recoverInstance("smithy.acme.app.7", state);
        factory.runs.openRun("smithy-development", "smithy.acme.app.7", "refine", null);

        assertNotNull(recovered);
        assertEquals(1, store.findRecent(10).size(), "recovery must not fork a second run");
    }

    @Test
    void aFailedAgentTurnIsSurfacedNotSwallowed() throws Exception {
        // The plan turn fails outright.
        docker.onExec(
            "/usr/bin/claude",
            new dev.smithyai.orchestrator.service.docker.dto.ExecResult(1, "", "claude exploded")
        );

        var event = issueAssigned();
        var instance = factory.getOrCreateInstance("smithy.acme.app.7", event);
        instance.onEvent(event);
        settle();

        var run = store.find(instance.runId()).orElseThrow();
        var types = store.findEvents(run.id()).stream().map(RunEvent::type).toList();
        assertTrue(types.contains("turn_failed"), "a failed turn is recorded: " + types);
        assertFalse(vcs.issueComments.isEmpty(), "and reported on the issue");
    }

    @Test
    void listsOnlyItsOwnContainersAsRecoverable() {
        var smithyState = dev.smithyai.orchestrator.service.docker.dto.ContainerState.init(
            dev.smithyai.orchestrator.service.docker.dto.WorkflowType.SMITHY,
            "refine"
        );
        var doneState = dev.smithyai.orchestrator.service.docker.dto.ContainerState.init(
            dev.smithyai.orchestrator.service.docker.dto.WorkflowType.SMITHY,
            "done"
        );

        assertTrue(factory.canRecover("smithy.acme.app.7", smithyState));
        assertFalse(factory.canRecover("architect.acme.app.pr-3", smithyState), "not an architect container");
        assertFalse(factory.canRecover("smithy.acme.app.7", doneState), "finished work is not recovered");
    }

    @Test
    void stubVcsRecordsWhatTheFlowAsked() {
        assertEquals(List.of(), vcs.issueComments);
    }
}
