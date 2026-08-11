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
import dev.smithyai.orchestrator.service.metrics.MetricsRecorder;
import dev.smithyai.orchestrator.workflow.flows.smithy.SmithyWorkflowFactory;
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
import org.springframework.mock.env.MockEnvironment;

/**
 * The same issue, driven through the Java flow and through the definition that
 * replaces it, against identical fakes.
 *
 * <p>This is the gate on deleting {@code workflow/flows/smithy/}. Porting a
 * 912-line class to YAML with no way to compare the two would be a rewrite with
 * no safety net, so what is compared here is what a person watching the
 * repository would see: the container that appeared, the commands run inside it,
 * the comments posted, and the branch and paths those commands used.
 *
 * <p>What is deliberately <em>not</em> compared is anything internal — the order
 * of store writes, the prompts, the number of agent turns. A port that produced
 * the same effects by a different route is a successful port.
 *
 * <p>It does not need Docker: {@link FakeDockerCli} replaces the process
 * boundary, so the real container-state handling, command construction and
 * Claude output parsing all still run on both sides.
 */
class SmithyParityTest {

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
        var ctx = new IssueContext(REPO, "7", "Add a thing", "Please add the thing.", "main");
        return new WorkflowEvent.PlanApproved(ctx, "alice");
    }

    private static WorkflowEvent.IssueAssigned issueAssigned() {
        var ctx = new IssueContext(REPO, "7", "Add a thing", "Please add the thing.", "main");
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

    // ── The Java flow ────────────────────────────────────────

    private Observed runJavaFlow(FakeDockerCli docker, boolean approve) throws Exception {
        var vcs = new StubVcsClient();
        var store = freshStore("java-" + System.identityHashCode(docker));
        scriptAPlan(docker);

        var factory = new SmithyWorkflowFactory(
            dockerConfig(),
            new CiConfig(false),
            new MetricsRecorder(new MockEnvironment().withProperty("METRICS_PATH", tempDir + "/metrics.jsonl")),
            new RepositoryConfigResolver(vcs),
            vcsProviderConfig(),
            new KnowledgebaseConfig(false, null, null),
            botConfig(),
            new ContainerService(dockerConfig(), claudeConfig(), vcsProviderConfig(), botConfig(), docker),
            new PromptRenderer(new DefaultResourceLoader()),
            vcs,
            vcs
        );
        factory.runs = new RunRecorder(store);

        var event = issueAssigned();
        var instance = factory.getOrCreateInstance(CONTAINER, event);
        instance.onEvent(event);
        Thread.sleep(600);
        if (approve) {
            scriptABuild(docker);
            instance.onEvent(planApproved());
            Thread.sleep(600);
        }

        var run = store.find(instance.runId()).orElseThrow();
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

    // ── The definition ───────────────────────────────────────

    private Observed runDefinition(FakeDockerCli docker, boolean approve) {
        var vcs = new StubVcsClient();
        var store = freshStore("yaml-" + System.identityHashCode(docker));
        scriptAPlan(docker);

        var containers = new ContainerService(dockerConfig(), claudeConfig(), vcsProviderConfig(), botConfig(), docker);
        var environments = new RunEnvironments(store, containers, new KnowledgebaseConfig(false, null, null));
        var prompts = new PromptRenderer(new DefaultResourceLoader());
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
                new RunSpawnAction(store),
                new RunAwaitAction(store),
                new RunWaveAction(store),
                new GateAwaitAction(store),
                new SignalEmitAction(store, null),
                new IssueCommentAction(vcs),
                new PrConversationAction(vcs),
                new RepoContextAction(new RepositoryConfigResolver(vcs), vcs),
                issues.issueCreateAction(vcs),
                issues.issueAssignAction(vcs),
                issues.issueLabelAction(vcs),
                issues.issueReadAction(vcs),
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
                review.attachmentsFetchAction(vcs, environments),
                review.fileDeleteAction(vcs),
                review.fileUrlAction(vcs),
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
            new WorkflowPolicyConfig(null, null, tempDir.resolve("no-such-dir").toString(), true),
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

    // ── The comparison ───────────────────────────────────────

    @Test
    void bothSidesCreateTheSameContainer() throws Exception {
        assertEquals(
            runJavaFlow(new FakeDockerCli(), false).containers(),
            runDefinition(new FakeDockerCli(), false).containers()
        );
    }

    @Test
    void bothSidesPostThePlanBackToTheIssue() throws Exception {
        var java = runJavaFlow(new FakeDockerCli(), false).issueComments();
        var yaml = runDefinition(new FakeDockerCli(), false).issueComments();

        assertEquals(1, java.size(), "the Java flow posts one comment: " + java);
        assertEquals(java.size(), yaml.size(), "and so does the definition: " + yaml);

        // The link and the open question are the content; exact whitespace is
        // not behaviour, so it is not what is compared.
        for (String comment : List.of(java.getFirst(), yaml.getFirst())) {
            assertTrue(comment.contains("Development plan:"), comment);
            assertTrue(comment.contains(".smithy/plans/7.md"), comment);
            assertTrue(comment.contains("Open Questions"), comment);
            assertTrue(comment.contains("Which cache should this use?"), comment);
        }
    }

    @Test
    void bothSidesWriteThePlanToTheSamePathAndPushItOnTheSameBranch() throws Exception {
        var java = runJavaFlow(new FakeDockerCli(), false).containerCommands();
        var yaml = runDefinition(new FakeDockerCli(), false).containerCommands();

        assertTrue(commandsContain(java, ".smithy/plans/7.md"), "Java writes the plan: " + java);
        assertTrue(commandsContain(yaml, ".smithy/plans/7.md"), "so does the definition: " + yaml);

        assertTrue(commandsContain(java, "smithy-commit-and-push"), java.toString());
        assertTrue(commandsContain(yaml, "smithy-commit-and-push"), yaml.toString());

        // The commit message names the issue the way the provider auto-links it.
        assertTrue(commandsContain(java, "Development plan for #7"), java.toString());
        assertTrue(commandsContain(yaml, "Development plan for #7"), yaml.toString());
    }

    @Test
    void bothSidesCloneTheSameBranchFromTheSameRepository() throws Exception {
        var javaDocker = new FakeDockerCli();
        var yamlDocker = new FakeDockerCli();
        runJavaFlow(javaDocker, false);
        runDefinition(yamlDocker, false);

        assertEquals("smithy/7-add-a-thing", branchFromCreate(javaDocker), "the Java flow's branch convention");
        assertEquals(branchFromCreate(javaDocker), branchFromCreate(yamlDocker), "and the definition's");
    }

    @Test
    void bothSidesLeaveTheRunInTheSameState() throws Exception {
        var java = runJavaFlow(new FakeDockerCli(), false);
        var yaml = runDefinition(new FakeDockerCli(), false);

        assertEquals("smithy-development", java.runWorkflow());
        assertEquals(java.runWorkflow(), yaml.runWorkflow());
        assertEquals("refine", java.runState(), "the Java flow reaches refine");
        assertEquals(java.runState(), yaml.runState(), "and so does the definition");
    }

    @Test
    void approvingThePlanOpensTheSamePullRequestOnBothSides() throws Exception {
        var java = runJavaFlow(new FakeDockerCli(), true);
        var yaml = runDefinition(new FakeDockerCli(), true);

        assertEquals(1, java.pullRequests().size(), "the Java flow opens one: " + java.pullRequests());
        assertEquals(java.pullRequests(), yaml.pullRequests(), "and the definition opens the same one");
        assertTrue(
            java.pullRequests().getFirst().startsWith("smithy/7-add-a-thing -> main:"),
            java.pullRequests().toString()
        );
    }

    @Test
    void bothSidesReachBuildAndSayImplementationStarted() throws Exception {
        var java = runJavaFlow(new FakeDockerCli(), true);
        var yaml = runDefinition(new FakeDockerCli(), true);

        assertEquals("build", java.runState(), "the Java flow reaches build");
        assertEquals(java.runState(), yaml.runState(), "and so does the definition");

        assertEquals(2, java.issueComments().size(), "plan, then implementation-started: " + java.issueComments());
        assertEquals(java.issueComments().size(), yaml.issueComments().size(), yaml.issueComments().toString());
        assertTrue(java.issueComments().get(1).contains("Plan approved"), java.issueComments().get(1));
        assertTrue(yaml.issueComments().get(1).contains("Plan approved"), yaml.issueComments().get(1));
    }

    @Test
    void bothSidesRebaseOntoTheBaseBranchBeforeOpeningTheRequest() throws Exception {
        var java = runJavaFlow(new FakeDockerCli(), true).containerCommands();
        var yaml = runDefinition(new FakeDockerCli(), true).containerCommands();

        assertTrue(commandsContain(java, "smithy-rebase-onto smithy/7-add-a-thing main"), java.toString());
        assertTrue(commandsContain(yaml, "smithy-rebase-onto smithy/7-add-a-thing main"), yaml.toString());
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
