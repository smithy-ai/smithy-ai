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
import dev.smithyai.orchestrator.runtime.store.*;
import dev.smithyai.orchestrator.service.claude.PromptRenderer;
import dev.smithyai.orchestrator.service.docker.ContainerService;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
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
 * The development workflow, end to end, against a simulated Docker daemon.
 *
 * <p>Every expected value here was first established by running the same event
 * stream through the Java flow this definition replaced and comparing the two;
 * that comparison lived at {@code SmithyParityTest} until the Java was deleted.
 * What survives is the half that is still worth running: the behaviour itself,
 * pinned.
 *
 * <p>What is asserted is what a person watching the repository would see — the
 * container that appeared, the branch it cloned, the commands run inside it, the
 * comments posted, the pull request opened — and not anything internal like the
 * order of store writes or the number of agent turns.
 *
 * <p>No Docker required: {@link FakeDockerCli} replaces the process boundary, so
 * the real container-state handling, command construction and Claude output
 * parsing all still run.
 */
class SmithyDefinitionTest {

    private static final RepoInfo REPO = new RepoInfo("acme", "app", "https://git.invalid/acme/app");
    private static final String CONTAINER = "smithy.acme.app.7";

    @TempDir
    Path tempDir;

    /** What either side did that anyone outside this process could see. */
    private record Observed(
        List<String> containers,
        List<String> issueComments,
        List<String> containerCommands,
        List<String> pullRequests,
        String runWorkflow,
        String runState
    ) {}

    private static WorkflowEvent.PlanApproved planApproved() {
        var ctx = new IssueContext(REPO, "7", "Add a thing", "Please add the thing.", "main", "smithy");
        return new WorkflowEvent.PlanApproved(ctx, "alice");
    }

    private static WorkflowEvent.IssueAssigned issueAssigned() {
        var ctx = new IssueContext(REPO, "7", "Add a thing", "Please add the thing.", "main", "smithy");
        return new WorkflowEvent.IssueAssigned(ctx, "https://git.invalid/acme/app");
    }

    /**
     * The same script for both sides: a plan turn, the plan file it wrote, and
     * an extraction turn reporting one open question.
     */
    private static void scriptAPlan(FakeDockerCli docker) {
        docker.enqueueClaudeText("Wrote the plan.");
        docker.enqueueClaudeStructured("{\"openQuestions\": [\"Which cache should this use?\"]}");
        docker.onExec(".claude/plans", new ExecResult(0, "/root/.claude/plans/plan.md", ""));
    }

    /** And the build turn that follows approval. */
    private static void scriptABuild(FakeDockerCli docker) {
        docker.enqueueClaudeText("Implemented it.");
    }

    // ── The definition ───────────────────────────────────────

    private Observed runDefinition(FakeDockerCli docker, boolean approve) {
        var vcs = new StubVcsClient();
        var store = freshStore("yaml-" + System.identityHashCode(docker));
        scriptAPlan(docker);

        var containers = new ContainerService(dockerConfig(), claudeConfig(), vcsProviderConfig(), botConfig(), docker);
        var environments = new RunEnvironments(store, containers, new KnowledgebaseConfig(false, null, null));
        var prompts = new PromptRenderer(new DefaultResourceLoader());
        // One stub answers for every connector in these tests.
        var trackers = new dev.smithyai.orchestrator.service.vcs.IssueTrackers(
            java.util.Map.of("forgejo", vcs, "gitlab", vcs, "jira", vcs),
            vcs
        );
        var renderer = new ExpressionRenderer();
        var foreach = new ForeachAction(null);

        var issues = new IssueActions();
        var prs = new PullRequestActions();
        var git = new GitActions();
        var state = new StateActions();
        var review = new ReviewActions();
        var ci = new CiActions();

        var actions = new ActionRegistry(
            List.of(
                foreach,
                new ContainerInitAction(environments, dockerConfig()),
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
                new PrConversationAction(vcs),
                new RepoContextAction(new RepositoryConfigResolver(vcs), vcs),
                issues.issueCreateAction(trackers),
                issues.issueAssignAction(trackers),
                issues.issueLabelAction(trackers),
                issues.issueReadAction(trackers),
                prs.prCreateAction(vcs),
                prs.prCommentAction(vcs),
                prs.prRequestReviewAction(vcs),
                prs.prReadAction(vcs),
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
                review.attachmentsFetchAction(trackers, environments),
                review.fileDeleteAction(vcs),
                review.fileUrlAction(vcs, vcsProviderConfig()),
                review.repoCloneUrlAction(vcs),
                review.prLinkAction(vcs, vcsProviderConfig()),
                ci.ciRetryGuardAction(store, new CiConfig(false)),
                ci.ciResetAction(store)
            )
        );

        var executor = new StepExecutor(actions, renderer, store);
        setExecutor(foreach, executor);

        var registry = new WorkflowRegistry(
            new WorkflowDefinitionLoader(new WorkflowDefinitionParser()),
            new CapabilityValidator(actions),
            // No override directory: the built-in definition is what is on trial.
            new WorkflowPolicyConfig(null, null, tempDir.resolve("no-such-dir").toString()),
            vcs,
            vcs,
            vcs
        );
        registry.loadAll();

        var engine = new RunEngine(
            registry,
            new WorkflowRouter(renderer),
            executor,
            store,
            environments,
            new RepositoryWorkflowLoader(vcs, new WorkflowDefinitionParser()),
            new EventDebouncer()
        );

        var outcome = engine.handle(issueAssigned()).stream().filter(RunEngine.Outcome::handled).findFirst();
        assertTrue(outcome.isPresent(), "the definition claimed the event");
        if (approve) {
            scriptABuild(docker);
            engine.handle(planApproved());
        }
        var run = store.find(outcome.get().runId()).orElseThrow();

        return new Observed(
            createdContainers(docker),
            List.copyOf(vcs.issueComments),
            containerCommands(docker),
            vcs.createdPrs
                .stream()
                .map(pr -> pr.headRef() + " -> " + pr.baseRef() + ": " + pr.title())
                .toList(),
            run.workflowName(),
            run.state()
        );
    }

    // ── Refining ─────────────────────────────────────────────

    @Test
    void assignmentCreatesTheWorkspaceContainer() {
        assertEquals(List.of(CONTAINER), runDefinition(new FakeDockerCli(), false).containers());
    }

    @Test
    void theBranchCarriesTheIssueAndASlugOfItsTitle() {
        var docker = new FakeDockerCli();
        runDefinition(docker, false);

        assertEquals("smithy/7-add-a-thing", branchFromCreate(docker));
    }

    @Test
    void thePlanIsPostedBackToTheIssueWithItsOpenQuestions() {
        var comments = runDefinition(new FakeDockerCli(), false).issueComments();

        assertEquals(1, comments.size(), comments.toString());
        var comment = comments.getFirst();
        assertTrue(comment.contains("Development plan:"), comment);
        assertTrue(comment.contains(".smithy/plans/7.md"), comment);
        assertTrue(comment.contains("Open Questions"), comment);
        assertTrue(comment.contains("Which cache should this use?"), comment);
    }

    @Test
    void thePlanIsWrittenToTheBranchAndPushedWithAMessageNamingTheIssue() {
        var commands = runDefinition(new FakeDockerCli(), false).containerCommands();

        assertTrue(commandsContain(commands, ".smithy/plans/7.md"), commands.toString());
        // "#7", not "7": the form a provider auto-links.
        assertTrue(commandsContain(commands, "Development plan for #7"), commands.toString());
    }

    @Test
    void refinementLeavesTheRunWaitingInRefine() {
        assertEquals("refine", runDefinition(new FakeDockerCli(), false).runState());
    }

    // ── Building ─────────────────────────────────────────────

    @Test
    void approvingThePlanOpensADraftRequestFromTheWorkBranch() {
        var pullRequests = runDefinition(new FakeDockerCli(), true).pullRequests();

        assertEquals(1, pullRequests.size(), pullRequests.toString());
        assertTrue(pullRequests.getFirst().startsWith("smithy/7-add-a-thing -> main:"), pullRequests.toString());
    }

    @Test
    void approvalIsAcknowledgedOnTheIssueThatWasApproved() {
        var comments = runDefinition(new FakeDockerCli(), true).issueComments();

        // Approving a plan has to have visible feedback where the approval
        // happened, not only on a pull request nobody has been told about.
        assertEquals(2, comments.size(), comments.toString());
        assertTrue(comments.get(1).contains("Plan approved"), comments.get(1));
    }

    @Test
    void theBaseBranchComesFromTheContainerNotTheEvent() {
        // An issue event rarely names a base branch; smithy-init resolves the
        // remote's default and records it. Trusting the event instead left
        // pr.create with no base at all.
        var commands = runDefinition(new FakeDockerCli(), true).containerCommands();

        assertTrue(commandsContain(commands, "cat /tmp/smithy-base-branch"), commands.toString());
    }

    @Test
    void theBranchIsRebasedOntoItsBaseBeforeTheRequestIsOpened() {
        var commands = runDefinition(new FakeDockerCli(), true).containerCommands();

        assertTrue(commandsContain(commands, "smithy-rebase-onto smithy/7-add-a-thing main"), commands.toString());
    }

    @Test
    void buildingLeavesTheRunInBuild() {
        assertEquals("build", runDefinition(new FakeDockerCli(), true).runState());
        assertEquals("smithy-development", runDefinition(new FakeDockerCli(), true).runWorkflow());
    }

    // ── Plumbing ─────────────────────────────────────────────

    /** The environment variable {@code smithy-init} clones from. */
    private static String branchFromCreate(FakeDockerCli docker) {
        for (var args : docker.invocations) {
            if (args.isEmpty() || !"create".equals(args.getFirst())) continue;
            for (String arg : args) {
                if (arg.startsWith("BRANCH=")) return arg.substring("BRANCH=".length());
            }
        }
        return null;
    }

    private static List<String> createdContainers(FakeDockerCli docker) {
        var names = new ArrayList<String>();
        for (var args : docker.invocations) {
            if (args.isEmpty() || !"create".equals(args.getFirst())) continue;
            int i = args.indexOf("--name");
            if (i >= 0 && i + 1 < args.size()) names.add(args.get(i + 1));
        }
        return names;
    }

    /** Commands run inside a container, as they would appear in a shell. */
    private static List<String> containerCommands(FakeDockerCli docker) {
        var commands = new ArrayList<String>();
        for (var args : docker.invocations) {
            if (args.isEmpty() || !"exec".equals(args.getFirst())) continue;
            String joined = String.join(" ", args);
            // The state file and the init probe are plumbing, not behaviour.
            if (joined.contains("smithy-state.json") || joined.contains("smithy-init-done")) continue;
            commands.add(joined);
        }
        return commands;
    }

    private static boolean commandsContain(List<String> commands, String needle) {
        return commands.stream().anyMatch(command -> command.contains(needle));
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

    private static void setExecutor(ForeachAction foreach, StepExecutor executor) {
        try {
            var field = ForeachAction.class.getDeclaredField("executor");
            field.setAccessible(true);
            field.set(foreach, executor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
