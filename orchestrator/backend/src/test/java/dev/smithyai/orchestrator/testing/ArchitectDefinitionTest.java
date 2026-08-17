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
 * The two architect workflows, against a simulated Docker daemon.
 *
 * <p>As with {@link SmithyDefinitionTest}, every expected value was established
 * by first running the same events through the Java flows these definitions
 * replaced and comparing the two. The comparison is gone with the Java; the
 * behaviour it pinned is not.
 */
class ArchitectDefinitionTest {

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

    private static WorkflowEvent.PrMerged prMerged() {
        var prc = new PrContext(REPO, 3, "Add caching", "Adds a cache layer.", true, "feature/cache", "main");
        return new WorkflowEvent.PrMerged(prc);
    }

    private static WorkflowEvent.ReviewRequested reviewRequested() {
        var prc = new PrContext(REPO, 3, "Add caching", "Adds a cache layer.", false, "feature/cache", "main");
        return new WorkflowEvent.ReviewRequested(prc);
    }

    // ── The definition ───────────────────────────────────────

    private Observed runDefinition(FakeDockerCli docker) {
        var vcs = new StubVcsClient();
        docker.enqueueClaudeStructured(REVIEW_JSON);

        var engine = engineFor(docker, vcs, "architect-yaml-" + System.identityHashCode(docker));
        var handled = engine.handle(reviewRequested()).stream().filter(RunEngine.Outcome::handled).toList();
        assertEquals(1, handled.size(), "exactly the reviewer claimed it: " + handled);
        assertEquals("architect-review", handled.getFirst().workflowName());
        return observe(docker, vcs);
    }

    /** The whole engine, wired the way the application wires it. */
    private RunEngine engineFor(FakeDockerCli docker, StubVcsClient vcs, String storeName) {
        var store = freshStore(storeName);
        var containers = new ContainerService(dockerConfig(), claudeConfig(), vcsProviderConfig(), botConfig(), docker);
        var environments = new RunEnvironments(store, containers, new KnowledgebaseConfig(false, null, null));
        // One stub answers for every connector in these tests.
        var trackerConnectors = java.util.Map.<String, dev.smithyai.orchestrator.service.vcs.IssueTrackerClient>of(
            "forgejo",
            vcs,
            "gitlab",
            vcs,
            "jira",
            vcs
        );
        var trackers = new dev.smithyai.orchestrator.service.vcs.IssueTrackers(
            java.util.Map.of("smithy", trackerConnectors, "architect", trackerConnectors),
            "smithy",
            "forgejo",
            (connector, actor) -> actor
        );
        var renderer = new ExpressionRenderer();
        var prompts = new PromptRenderer(new DefaultResourceLoader());
        var review = new ReviewActions();
        var state = new StateActions();

        var actions = new ActionRegistry(
            List.of(
                new ForeachAction(null),
                new ContainerInitAction(environments, dockerConfig(), TestActors.defaults()),
                new AgentRunAction(environments, prompts),
                new AgentRunStructuredAction(environments, prompts),
                new AgentNewSessionAction(environments),
                new CorrelateAction(store),
                new RunSpawnAction(store, null),
                new RunAwaitAction(store),
                new RunWaveAction(store),
                new GateAwaitAction(store),
                new SignalEmitAction(store, null),
                new IssueCommentAction(trackers),
                new PrConversationAction(vcs.asRegistry()),
                new RepoContextAction(new RepositoryConfigResolver(vcs), vcs),
                new IssueActions().issueCreateAction(trackers),
                new IssueActions().issueAssignAction(trackers),
                new IssueActions().issueLabelAction(trackers),
                new IssueActions().issueReadAction(trackers),
                new PullRequestActions().prCreateAction(vcs.asRegistry()),
                new PullRequestActions().prCommentAction(vcs.asRegistry()),
                new PullRequestActions().prRequestReviewAction(vcs.asRegistry()),
                new PullRequestActions().prReadAction(vcs.asRegistry()),
                new GitActions().gitPullAction(environments),
                new GitActions().gitPushAction(environments),
                new GitActions().gitStatusAction(environments),
                new GitActions().execAction(environments),
                new GitActions().agentEnsureCommittedAction(environments),
                new GitActions().instanceDestroyAction(environments),
                state.stateSetAction(store),
                state.stateVarAction(store),
                state.metricsRecordAction(store),
                review.commentReactAction(vcs.asRegistry()),
                review.prReplyAction(vcs.asRegistry()),
                review.prIsAssignedAction(vcs.asRegistry()),
                review.prSetAssigneesAction(vcs.asRegistry()),
                review.prFindByHeadAction(vcs.asRegistry()),
                review.prReviewCommentsAction(vcs.asRegistry()),
                review.prReviewAction(vcs.asRegistry()),
                review.attachmentsFetchAction(trackers, environments),
                review.fileDeleteAction(vcs.asRegistry()),
                review.fileUrlAction(vcs.asRegistry()),
                review.repoCloneUrlAction(vcs.asRegistry()),
                review.prLinkAction(vcs.asRegistry()),
                new CiActions().ciRetryGuardAction(store, new CiConfig(false)),
                new CiActions().ciResetAction(store)
            )
        );

        var registry = new WorkflowRegistry(
            new WorkflowDefinitionLoader(new WorkflowDefinitionParser()),
            new CapabilityValidator(actions),
            new WorkflowPolicyConfig(null, null, tempDir.resolve("no-such-dir").toString()),
            vcs,
            vcs,
            vcs
        );
        registry.loadAll();

        return new RunEngine(
            registry,
            new WorkflowRouter(renderer),
            new StepExecutor(actions, renderer, store),
            store,
            environments,
            new RepositoryWorkflowLoader(vcs, new WorkflowDefinitionParser()),
            new EventDebouncer()
        );
    }

    // ── Reviewing ────────────────────────────────────────────

    @Test
    void aReviewRequestBuildsAWorkspaceForThatPullRequest() {
        assertEquals(List.of(CONTAINER), runDefinition(new FakeDockerCli()).containers());
    }

    @Test
    void theGuidelinesRepositoryIsClonedBesideTheBranch() {
        var mounts = runDefinition(new FakeDockerCli()).extraRepoMounts();

        // Answering "does this follow our guidelines" needs both repositories in
        // one workspace; the mount path is what the prompt refers to.
        assertEquals(1, mounts.size(), mounts.toString());
        assertTrue(mounts.getFirst().contains("/context-repo"), mounts.toString());
        assertTrue(mounts.getFirst().contains("app-context"), mounts.toString());
    }

    @Test
    void theReviewerWorksAsItselfRatherThanAsTheAgentItReviews() {
        var docker = new FakeDockerCli();
        runDefinition(docker);

        var env = docker.invocations
            .stream()
            .filter(args -> !args.isEmpty() && args.getFirst().equals("create"))
            .flatMap(java.util.List::stream)
            .toList();

        // A reader of the repository has to be able to tell the reviewer from
        // the author, and that means its own account and its own token.
        assertTrue(env.contains("GIT_EMAIL=architect@localhost"), env.toString());
        assertTrue(env.contains("VCS_TOKEN=architect-token"), env.toString());
    }

    @Test
    void theReviewIsPostedWithItsSummary() {
        assertEquals(List.of("Two things to fix."), runDefinition(new FakeDockerCli()).reviewSummaries());
    }

    @Test
    void commentsAreAnchoredToTheLinesTheyAreAbout() {
        // Inline anchoring is the whole value of a review over a comment.
        assertEquals(List.of("src/Main.java:12"), runDefinition(new FakeDockerCli()).reviewAnchors());
    }

    // ── Learning from a merged pull request ──────────────────

    /** What the agent decided to write down, in the shape each side asks for. */
    private static final String LEARNING_JSON = """
        {"action": "UPDATE",
         "title": "Cache access goes through the repository layer",
         "description": "PR #3 argued about this twice."}
        """;

    private Observed runDefinitionLearn(FakeDockerCli docker) {
        var vcs = new StubVcsClient();
        docker.enqueueClaudeStructured(LEARNING_JSON);
        docker.onExec("symbolic-ref", new dev.smithyai.orchestrator.service.docker.dto.ExecResult(0, "main", ""));

        var engine = engineFor(docker, vcs, "learn-yaml-" + System.identityHashCode(docker));
        var handled = engine.handle(prMerged()).stream().filter(RunEngine.Outcome::handled).toList();
        assertEquals(1, handled.size(), "exactly the learner claimed it: " + handled);
        assertEquals("architect-learn", handled.getFirst().workflowName());
        return observeLearn(docker, vcs);
    }

    @Test
    void aMergedRequestProposesTheGuidelineChangeOnTheGuidelinesRepository() {
        var proposals = runDefinitionLearn(new FakeDockerCli()).reviewSummaries();

        // Proposed, never merged: the people who own the guidelines decide.
        assertEquals(1, proposals.size(), proposals.toString());
        assertTrue(
            proposals.getFirst().contains("Cache access goes through the repository layer"),
            proposals.toString()
        );
        assertTrue(proposals.getFirst().endsWith("-> main"), "onto the guidelines default branch: " + proposals);
    }

    @Test
    void theGuidelinesBranchIsPushedBeforeItIsProposed() {
        var commands = runDefinitionLearn(new FakeDockerCli()).reviewAnchors();

        assertTrue(commands.stream().anyMatch(c -> c.contains("git push")), commands.toString());
    }

    /** Reuses the Observed shape: summaries are PR titles here, anchors are commands. */
    private static Observed observeLearn(FakeDockerCli docker, StubVcsClient vcs) {
        var containers = new ArrayList<String>();
        var mounts = new ArrayList<String>();
        var commands = new ArrayList<String>();
        for (var args : docker.invocations) {
            if (!args.isEmpty() && "create".equals(args.getFirst())) {
                int i = args.indexOf("--name");
                if (i >= 0 && i + 1 < args.size()) containers.add(args.get(i + 1));
                for (String arg : args) {
                    if (arg.startsWith("EXTRA_REPOS=") && arg.length() > "EXTRA_REPOS=".length()) {
                        mounts.add(arg.substring("EXTRA_REPOS=".length()));
                    }
                }
            }
            if (!args.isEmpty() && "exec".equals(args.getFirst())) commands.add(String.join(" ", args));
        }
        return new Observed(
            containers,
            mounts,
            vcs.createdPrs
                .stream()
                .map(pr -> pr.title() + " -> " + pr.baseRef())
                .toList(),
            commands
        );
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
            new BotConfig.BotEntry("architect", "architect@localhost"),
            new BotConfig.BotEntry("coordinator", "coordinator@localhost")
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
                "architect-token",
                "coordinator-token"
            ),
            null,
            null,
            null
        );
    }
}
