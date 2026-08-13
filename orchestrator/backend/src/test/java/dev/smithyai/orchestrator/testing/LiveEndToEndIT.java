package dev.smithyai.orchestrator.testing;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.*;
import dev.smithyai.orchestrator.model.IssueContext;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.actions.*;
import dev.smithyai.orchestrator.runtime.definition.RepositoryWorkflowLoader;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionLoader;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionParser;
import dev.smithyai.orchestrator.runtime.engine.*;
import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.runtime.store.RunEnvironment;
import dev.smithyai.orchestrator.runtime.store.RunEvent;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import dev.smithyai.orchestrator.service.claude.PromptRenderer;
import dev.smithyai.orchestrator.service.docker.ContainerService;
import dev.smithyai.orchestrator.service.docker.DockerCli;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.forgejo.ForgejoClient;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * One issue, all the way through, against everything real.
 *
 * <p>A real Forgejo instance, a real Docker daemon, a real Claude session. The
 * rest of the suite replaces one of those with a fake so it can run anywhere and
 * in seconds; this replaces none of them, and is therefore the only thing that
 * can tell you the parts actually fit together.
 *
 * <p>The webhook is the one layer not exercised: the event is handed to the
 * engine directly, because a public Forgejo cannot reach an orchestrator on a
 * laptop. Mapping a payload to an event has golden tests per provider.
 *
 * <p>Skipped unless the environment names a repository and supplies credentials
 * for it. It writes to that repository — a branch, a plan file, a comment, a
 * pull request — so point it at something you do not mind it touching:
 *
 * <pre>
 * SMITHY_IT_URL=https://git.example.com
 * SMITHY_IT_TOKEN=...          # a Forgejo token for the bot user
 * SMITHY_IT_REPO=owner/repo
 * SMITHY_IT_ISSUE=12           # an open issue assigned to the bot
 * CLAUDE_CODE_OAUTH_TOKEN=...
 * </pre>
 */
class LiveEndToEndIT {

    private static String url;
    private static String token;
    private static String owner;
    private static String repo;
    private static String issueRef;
    private static String claudeToken;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void requireLiveEnvironment() {
        url = System.getenv("SMITHY_IT_URL");
        token = System.getenv("SMITHY_IT_TOKEN");
        claudeToken = System.getenv("CLAUDE_CODE_OAUTH_TOKEN");
        String slug = System.getenv("SMITHY_IT_REPO");
        issueRef = System.getenv("SMITHY_IT_ISSUE");

        Assumptions.assumeTrue(url != null && token != null, "no live Forgejo configured");
        Assumptions.assumeTrue(claudeToken != null && !claudeToken.isBlank(), "no Claude token");
        Assumptions.assumeTrue(slug != null && slug.contains("/"), "SMITHY_IT_REPO must be owner/repo");
        Assumptions.assumeTrue(issueRef != null, "SMITHY_IT_ISSUE must name an open issue");
        owner = slug.substring(0, slug.indexOf('/'));
        repo = slug.substring(slug.indexOf('/') + 1);

        Assumptions.assumeTrue(dockerAvailable(), "Docker is not running");
    }

    /**
     * Assignment through to a plan on the branch and a comment on the issue.
     *
     * <p>Generous timeout: this waits on a real planning turn, which is minutes,
     * not seconds.
     */
    @Test
    @Timeout(900)
    void assigningAnIssuePlansItAndPostsThePlanBack() {
        var vcs = new ForgejoClient(url, token);
        var store = freshStore();
        var engine = engine(store, vcs, vcs);

        var issue = vcs.getIssue(owner, repo, issueRef);
        var info = new RepoInfo(owner, repo, vcs.cloneUrl(owner, repo));
        var context = new IssueContext(info, issueRef, issue.title(), issue.body(), issue.baseBranch());

        int commentsBefore = vcs.getIssueComments(owner, repo, issueRef).size();

        var outcomes = engine.handle(new WorkflowEvent.IssueAssigned(context, url + "/" + owner + "/" + repo));

        var handled = outcomes.stream().filter(RunEngine.Outcome::handled).findFirst();
        assertTrue(handled.isPresent(), "the definition claimed the event: " + outcomes);
        var run = store.find(handled.get().runId()).orElseThrow();
        System.out.println("[it] run " + run.id() + " is in " + run.state());

        assertEquals("smithy-development", run.workflowName());
        assertEquals("refine", run.state());

        // A container was really created and is really held by this run.
        var containers = store.findEnvironmentNames(run.id(), RunEnvironment.CONTAINER);
        assertEquals(1, containers.size(), "the run holds its container");
        System.out.println("[it] container " + containers.getFirst());

        // The plan reached the branch, read back through the provider API.
        String branch = "smithy/" + issueRef + "-" + slug(issue.title());
        String planPath = ".smithy/plans/" + issueRef + ".md";
        var plan = vcs.readRepositoryFile(owner, repo, planPath, branch);
        assertTrue(plan.isPresent(), "no " + planPath + " on " + branch);
        assertFalse(plan.get().isBlank(), "the plan file is empty");
        System.out.println("[it] plan is " + plan.get().length() + " chars on " + branch);

        // And the issue was told about it.
        var comments = vcs.getIssueComments(owner, repo, issueRef);
        assertTrue(comments.size() > commentsBefore, "no new comment on the issue");
        String latest = comments.getLast().body();
        assertTrue(latest.contains("Development plan"), latest);
        assertTrue(latest.contains(planPath), latest);

        var timeline = store.findEvents(run.id()).stream().map(RunEvent::type).toList();
        assertTrue(timeline.contains("plan_posted"), "timeline: " + timeline);
        System.out.println("[it] timeline " + timeline);
    }

    /**
     * The rest of it: approval opens a draft pull request off a rebased branch,
     * with the implementation on it.
     *
     * <p>Runs in the same process as the planning turn so it inherits the run —
     * approval on a run that never planned has nothing to build from.
     */
    @Test
    @Timeout(900)
    void approvingThePlanBuildsItAndOpensADraftRequest() {
        var vcs = new ForgejoClient(url, token);
        var store = freshStore();
        var engine = engine(store, vcs, vcs);

        var issue = vcs.getIssue(owner, repo, issueRef);
        var info = new RepoInfo(owner, repo, vcs.cloneUrl(owner, repo));
        var context = new IssueContext(info, issueRef, issue.title(), issue.body(), issue.baseBranch());

        engine.handle(new WorkflowEvent.IssueAssigned(context, url + "/" + owner + "/" + repo));
        var outcomes = engine.handle(new WorkflowEvent.PlanApproved(context, "tomas"));

        var handled = outcomes.stream().filter(RunEngine.Outcome::handled).findFirst();
        assertTrue(handled.isPresent(), "approval was claimed: " + outcomes);
        var run = store.find(handled.get().runId()).orElseThrow();
        System.out.println("[it] run " + run.id() + " is in " + run.state());
        assertEquals("build", run.state());

        String branch = "smithy/" + issueRef + "-" + slug(issue.title());
        var pr = vcs.findPrByHead(owner, repo, branch);
        assertNotNull(pr, "no pull request from " + branch);
        System.out.println("[it] opened !" + pr.number() + " " + pr.headRef() + " -> " + pr.baseRef());
        assertEquals(branch, pr.headRef());

        // The build turn committed something beyond the plan.
        var files = vcs.listRepositoryFiles(owner, repo, "", branch);
        System.out.println("[it] branch now holds " + files);
        assertTrue(files.size() > 1, "the build turn left only the plan behind: " + files);

        var timeline = store.findEvents(run.id()).stream().map(RunEvent::type).toList();
        assertTrue(timeline.contains("pr_opened"), "timeline: " + timeline);
        assertTrue(timeline.contains("build_completed"), "timeline: " + timeline);
        System.out.println("[it] timeline " + timeline);
    }

    // ── Wiring: the same beans the application uses ──────────

    private RunEngine engine(RunStore store, VcsClient vcs, IssueTrackerClient issues) {
        var dockerConfig = new DockerConfig(
            "docker",
            System.getenv().getOrDefault("SMITHY_IT_NETWORK", "bridge"),
            System.getenv().getOrDefault("SMITHY_IT_IMAGE", "claude-task-default:test"),
            null
        );
        var containers = new ContainerService(
            dockerConfig,
            new ClaudeConfig(claudeToken, null, System.getenv().getOrDefault("SMITHY_IT_MODEL", "claude-opus-5")),
            vcsConfig(),
            botConfig(),
            new DockerCli(dockerConfig)
        );
        var environments = new RunEnvironments(store, containers, new KnowledgebaseConfig(false, null, null));
        var prompts = new PromptRenderer(new DefaultResourceLoader());
        var renderer = new ExpressionRenderer();
        var foreach = new ForeachAction(null);

        var issueActions = new IssueActions();
        var prActions = new PullRequestActions();
        var git = new GitActions();
        var state = new StateActions();
        var review = new ReviewActions();
        var ci = new CiActions();

        var actions = new ActionRegistry(
            List.of(
                foreach,
                new ContainerInitAction(environments, dockerConfig),
                new AgentRunAction(environments, prompts),
                new AgentRunStructuredAction(environments, prompts),
                new AgentNewSessionAction(environments),
                new CorrelateAction(store),
                new RunSpawnAction(store, null),
                new RunAwaitAction(store),
                new RunWaveAction(store),
                new GateAwaitAction(store),
                new SignalEmitAction(store, null),
                new IssueCommentAction(issues, issues),
                new PrConversationAction(vcs),
                new RepoContextAction(new RepositoryConfigResolver(vcs), vcs),
                issueActions.issueCreateAction(issues, issues),
                issueActions.issueAssignAction(issues, issues),
                issueActions.issueLabelAction(issues, issues),
                issueActions.issueReadAction(issues, issues),
                prActions.prCreateAction(vcs),
                prActions.prCommentAction(vcs),
                prActions.prRequestReviewAction(vcs),
                prActions.prReadAction(vcs),
                git.gitPullAction(environments),
                git.gitPushAction(environments),
                git.gitStatusAction(environments),
                git.execAction(environments),
                git.agentEnsureCommittedAction(environments),
                git.instanceDestroyAction(environments),
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
                review.attachmentsFetchAction(issues, issues, environments),
                review.fileDeleteAction(vcs),
                review.fileUrlAction(vcs, vcsConfig()),
                review.repoCloneUrlAction(vcs),
                review.prLinkAction(vcs, vcsConfig()),
                ci.ciRetryGuardAction(store, new CiConfig(false)),
                ci.ciResetAction(store)
            )
        );

        var executor = new StepExecutor(actions, renderer, store);
        setExecutor(foreach, executor);

        var registry = new WorkflowRegistry(
            new WorkflowDefinitionLoader(new WorkflowDefinitionParser()),
            new CapabilityValidator(actions),
            new WorkflowPolicyConfig(null, null, tempDir.resolve("no-overrides").toString()),
            vcs,
            issues,
            issues
        );
        registry.loadAll();

        return new RunEngine(
            registry,
            new WorkflowRouter(renderer),
            executor,
            store,
            environments,
            new RepositoryWorkflowLoader(vcs, new WorkflowDefinitionParser()),
            new EventDebouncer()
        );
    }

    /**
     * A temporary database unless SMITHY_IT_DB names one, which is how a run
     * this produces can be looked at in the dashboard afterwards.
     */
    private RunStore freshStore() {
        String path = System.getenv().getOrDefault("SMITHY_IT_DB", tempDir.resolve("live.db").toString());
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + path + "?foreign_keys=on");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        return new RunStore(JdbcClient.create((DataSource) dataSource), new ObjectMapper());
    }

    private static VcsProviderConfig vcsConfig() {
        return new VcsProviderConfig(
            "forgejo",
            null,
            new VcsProviderConfig.ForgejoProviderConfig(url, url, null, token, token),
            null,
            null,
            null
        );
    }

    private static BotConfig botConfig() {
        return new BotConfig(
            new BotConfig.BotEntry("smithy", "smithy@localhost"),
            new BotConfig.BotEntry("architect", "architect@localhost")
        );
    }

    /** Mirrors the definition's {@code | slug} filter, to locate the branch. */
    private static String slug(String title) {
        String slug = title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return slug.length() > 40 ? slug.substring(0, 40) : slug;
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

    private static boolean dockerAvailable() {
        try {
            return (
                new ProcessBuilder("sh", "-c", "docker info")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor() ==
                0
            );
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
