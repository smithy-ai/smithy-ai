package dev.smithyai.orchestrator.testing;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.*;
import dev.smithyai.orchestrator.model.PrContext;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.actions.*;
import dev.smithyai.orchestrator.runtime.definition.RepositoryWorkflowLoader;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionLoader;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionParser;
import dev.smithyai.orchestrator.runtime.engine.*;
import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.runtime.store.*;
import dev.smithyai.orchestrator.service.claude.PromptRenderer;
import dev.smithyai.orchestrator.service.docker.ContainerService;
import dev.smithyai.orchestrator.workflow.flows.architect.ArchitectReviewFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The same review request, through the Java flow and through the definition.
 *
 * <p>Same idea as {@link SmithyParityTest} and the same gate: the architect
 * flows are smaller, but "smaller" is not evidence, and deleting them on the
 * strength of the port reading correctly would be exactly the rewrite-without-a-
 * safety-net the plan warned about.
 *
 * <p>The reviewer's observable output is narrow, which makes it easy to compare
 * completely: a container with the guidelines cloned beside the branch, and a
 * review posted with a summary and comments anchored to lines.
 */
class ArchitectParityTest {

    private static final RepoInfo REPO = new RepoInfo("acme", "app", "https://git.invalid/acme/app");
    private static final String CONTAINER = "architect.acme.app.pr-3";

    /** What the agent came back with, in the shape each side asks for. */
    private static final String REVIEW_JSON = """
        {"summary": "Two things to fix.",
         "comments": [{"path": "src/Main.java", "line": 12, "body": "This bypasses the cache."}]}
        """;

    @TempDir
    Path tempDir;

    private record Observed(
        List<String> containers,
        List<String> extraRepoMounts,
        List<String> reviewSummaries,
        List<String> reviewAnchors
    ) {}

    private static WorkflowEvent.ReviewRequested reviewRequested() {
        var prc = new PrContext(REPO, 3, "Add caching", "Adds a cache layer.", false, "feature/cache", "main");
        return new WorkflowEvent.ReviewRequested(prc);
    }

    // ── The Java flow ────────────────────────────────────────

    private Observed runJavaFlow(FakeDockerCli docker) throws Exception {
        var vcs = new StubVcsClient();
        docker.enqueueClaudeStructured(REVIEW_JSON);

        var factory = new ArchitectReviewFactory(
            dockerConfig(),
            vcsProviderConfig(),
            botConfig(),
            new ContainerService(dockerConfig(), claudeConfig(), vcsProviderConfig(), botConfig(), docker),
            new RepositoryConfigResolver(vcs),
            new PromptRenderer(new DefaultResourceLoader()),
            vcs,
            vcs
        );
        factory.runs = new RunRecorder(freshStore("architect-java-" + System.identityHashCode(docker)));

        var event = reviewRequested();
        var instance = factory.getOrCreateInstance(CONTAINER, event);
        instance.onEvent(event);
        Thread.sleep(600);

        return observe(docker, vcs);
    }

    // ── The definition ───────────────────────────────────────

    private Observed runDefinition(FakeDockerCli docker) {
        var vcs = new StubVcsClient();
        docker.enqueueClaudeStructured(REVIEW_JSON);

        var store = freshStore("architect-yaml-" + System.identityHashCode(docker));
        var containers = new ContainerService(dockerConfig(), claudeConfig(), vcsProviderConfig(), botConfig(), docker);
        var environments = new RunEnvironments(store, containers, new KnowledgebaseConfig(false, null, null));
        var renderer = new ExpressionRenderer();
        var prompts = new PromptRenderer(new DefaultResourceLoader());
        var review = new ReviewActions();
        var state = new StateActions();

        var actions = new ActionRegistry(
            List.of(
                new ForeachAction(null),
                new ContainerInitAction(environments, dockerConfig()),
                new AgentRunAction(environments, prompts),
                new AgentRunStructuredAction(environments, prompts),
                new AgentNewSessionAction(environments),
                new CorrelateAction(store),
                new RunSpawnAction(store),
                new RunAwaitAction(store),
                new RunWaveAction(store),
                new GateAwaitAction(store),
                new SignalEmitAction(store, null),
                new IssueCommentAction(vcs),
                new PrConversationAction(vcs),
                new RepoContextAction(new RepositoryConfigResolver(vcs), vcs),
                new IssueActions().issueCreateAction(vcs),
                new IssueActions().issueAssignAction(vcs),
                new IssueActions().issueLabelAction(vcs),
                new IssueActions().issueReadAction(vcs),
                new PullRequestActions().prCreateAction(vcs),
                new PullRequestActions().prCommentAction(vcs),
                new PullRequestActions().prRequestReviewAction(vcs),
                new PullRequestActions().prReadAction(vcs),
                new GitActions().gitPullAction(environments),
                new GitActions().gitPushAction(environments),
                new GitActions().gitStatusAction(environments),
                new GitActions().execAction(environments),
                new GitActions().agentEnsureCommittedAction(environments),
                new GitActions().instanceDestroyAction(environments),
                state.stateSetAction(store),
                state.stateVarAction(store),
                state.metricsRecordAction(store),
                review.commentReactAction(vcs),
                review.prReplyAction(vcs),
                review.prIsAssignedAction(vcs),
                review.prSetAssigneesAction(vcs),
                review.prFindByHeadAction(vcs),
                review.prReviewCommentsAction(vcs),
                review.prReviewAction(vcs),
                review.attachmentsFetchAction(vcs, environments),
                review.fileDeleteAction(vcs),
                review.fileUrlAction(vcs),
                review.prLinkAction(vcs, vcsProviderConfig()),
                new CiActions().ciRetryGuardAction(store, new CiConfig(false)),
                new CiActions().ciResetAction(store)
            )
        );

        var registry = new WorkflowRegistry(
            new WorkflowDefinitionLoader(new WorkflowDefinitionParser()),
            new CapabilityValidator(actions),
            new WorkflowPolicyConfig(null, null, tempDir.resolve("no-such-dir").toString(), true),
            vcs,
            vcs
        );
        registry.loadAll();

        var engine = new RunEngine(
            registry,
            new WorkflowRouter(renderer),
            new StepExecutor(actions, renderer, store),
            store,
            environments,
            new RepositoryWorkflowLoader(vcs, new WorkflowDefinitionParser()),
            new EventDebouncer()
        );

        var handled = engine.handle(reviewRequested()).stream().filter(RunEngine.Outcome::handled).toList();
        assertEquals(1, handled.size(), "exactly the reviewer claimed it: " + handled);
        assertEquals("architect-review", handled.getFirst().workflowName());

        return observe(docker, vcs);
    }

    // ── The comparison ───────────────────────────────────────

    @Test
    void bothSidesBuildTheSameWorkspace() throws Exception {
        var java = runJavaFlow(new FakeDockerCli());
        var yaml = runDefinition(new FakeDockerCli());

        assertEquals(List.of(CONTAINER), java.containers());
        assertEquals(java.containers(), yaml.containers());
    }

    @Test
    void bothSidesCloneTheGuidelinesRepositoryBesideTheBranch() throws Exception {
        var java = runJavaFlow(new FakeDockerCli());
        var yaml = runDefinition(new FakeDockerCli());

        // Reviewing "does this follow our guidelines" needs both repositories
        // in one workspace; the mount path is what the prompt refers to.
        assertEquals(1, java.extraRepoMounts().size(), "the Java flow mounts one: " + java.extraRepoMounts());
        assertTrue(java.extraRepoMounts().getFirst().contains("/context-repo"), java.extraRepoMounts().toString());
        assertEquals(java.extraRepoMounts(), yaml.extraRepoMounts());
    }

    @Test
    void bothSidesPostTheSameReview() throws Exception {
        var java = runJavaFlow(new FakeDockerCli());
        var yaml = runDefinition(new FakeDockerCli());

        assertEquals(List.of("Two things to fix."), java.reviewSummaries());
        assertEquals(java.reviewSummaries(), yaml.reviewSummaries());
    }

    @Test
    void bothSidesAnchorTheCommentToTheSameLine() throws Exception {
        var java = runJavaFlow(new FakeDockerCli());
        var yaml = runDefinition(new FakeDockerCli());

        // Inline anchoring is the whole value of a review over a comment.
        assertEquals(List.of("src/Main.java:12"), java.reviewAnchors());
        assertEquals(java.reviewAnchors(), yaml.reviewAnchors());
    }

    // ── Plumbing ─────────────────────────────────────────────

    private static Observed observe(FakeDockerCli docker, StubVcsClient vcs) {
        var containers = new ArrayList<String>();
        var mounts = new ArrayList<String>();
        for (var args : docker.invocations) {
            if (args.isEmpty() || !"create".equals(args.getFirst())) continue;
            int i = args.indexOf("--name");
            if (i >= 0 && i + 1 < args.size()) containers.add(args.get(i + 1));
            for (String arg : args) {
                if (arg.startsWith("EXTRA_REPOS=") && arg.length() > "EXTRA_REPOS=".length()) {
                    mounts.add(arg.substring("EXTRA_REPOS=".length()));
                }
            }
        }
        return new Observed(
            containers,
            mounts,
            vcs.postedReviews.stream().map(StubVcsClient.PostedReview::summary).toList(),
            vcs.postedReviews
                .stream()
                .flatMap(posted -> posted.comments().stream())
                .map(comment -> comment.path() + ":" + comment.newPosition())
                .toList()
        );
    }

    private RunStore freshStore(String name) {
        var dataSource = new DriverManagerDataSource(
            "jdbc:sqlite:" + tempDir.resolve(name + ".db") + "?foreign_keys=on"
        );
        dataSource.setDriverClassName("org.sqlite.JDBC");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        return new RunStore(JdbcClient.create((DataSource) dataSource), new ObjectMapper());
    }

    private static DockerConfig dockerConfig() {
        return new DockerConfig("docker", "smithy-net", "claude-task:test", null);
    }

    private static ClaudeConfig claudeConfig() {
        return new ClaudeConfig("test-token", null, "claude-opus-5");
    }

    private static BotConfig botConfig() {
        return new BotConfig(
            new BotConfig.BotEntry("smithy", "smithy@localhost"),
            new BotConfig.BotEntry("architect", "architect@localhost")
        );
    }

    private static VcsProviderConfig vcsProviderConfig() {
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
}
