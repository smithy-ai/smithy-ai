package dev.smithyai.orchestrator.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.CiConfig;
import dev.smithyai.orchestrator.config.ConnectorActorConfig;
import dev.smithyai.orchestrator.config.ConnectorConfig;
import dev.smithyai.orchestrator.config.OrchestratorConfig;
import dev.smithyai.orchestrator.config.RepositoryCatalogConfig;
import dev.smithyai.orchestrator.config.RepositoryConfigResolver;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
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
          storyRepos: [acme/product]
          repositoryCatalog: acme-product
        """;

    @TempDir
    Path tempDir;

    private RunStore store;
    private WorkflowRegistry workflows;
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

        // One stub answers for every connector in these tests.
        var trackerConnectors = Map.<String, dev.smithyai.orchestrator.service.vcs.IssueTrackerClient>of(
            "forgejo",
            vcs,
            "forgejo-main",
            vcs,
            "gitlab",
            vcs,
            "jira",
            vcs
        );
        var trackers = new dev.smithyai.orchestrator.service.vcs.IssueTrackers(
            Map.of("smithy", trackerConnectors, "coordinator", trackerConnectors),
            "smithy",
            "forgejo",
            (connector, actor) -> actor
        );
        var vcsClients = new dev.smithyai.orchestrator.service.vcs.VcsClients(
            Map.of(
                "smithy",
                Map.of("default", vcs, "forgejo", vcs, "forgejo-main", vcs),
                "coordinator",
                Map.of("default", vcs, "forgejo", vcs, "forgejo-main", vcs)
            ),
            "smithy",
            "default"
        );
        var renderer = new ExpressionRenderer();
        var stateActions = new StateActions();
        var issueActions = new IssueActions();
        var prActions = new PullRequestActions();
        var git = new GitActions();
        var review = new ReviewActions();
        var ci = new CiActions();
        var prompts = new dev.smithyai.orchestrator.service.claude.PromptRenderer(
            new org.springframework.core.io.DefaultResourceLoader()
        );
        var environments = new RunEnvironments(store, null, null);
        var foreach = new ForeachAction(null);
        var deploymentConfig = new OrchestratorConfig(
            OrchestratorConfig.API_VERSION,
            OrchestratorConfig.KIND,
            null,
            null,
            null,
            null,
            // The actors this coordinator and its children act as. A connector
            // that configures neither is a deployment the workflow cannot run
            // in, and the registry now says so at load.
            Map.of(
                "forgejo-main",
                new ConnectorConfig(
                    "forgejo",
                    "",
                    null,
                    null,
                    Map.of(
                        "smithy",
                        new ConnectorActorConfig("smithy", null, null, null, null, null),
                        "coordinator",
                        new ConnectorActorConfig("coordinator", null, null, null, null, null)
                    ),
                    null,
                    null
                )
            ),
            new dev.smithyai.orchestrator.config.DefaultsConfig("forgejo-main", "event.source", "smithy"),
            null,
            Map.of(
                "acme-product",
                List.of(
                    new RepositoryCatalogConfig("forgejo-main", "acme", "api", "The HTTP API"),
                    new RepositoryCatalogConfig("forgejo-main", "acme", "web", "The web client")
                )
            ),
            null,
            null,
            null
        );
        // The registry answers what provider a connector speaks, so a
        // spawned child does not depend on its parent restating it.
        var connectors = new dev.smithyai.orchestrator.config.ConnectorRegistry(
            deploymentConfig,
            new org.springframework.mock.env.MockEnvironment()
        );
        var spawn = new RunSpawnAction(store, null, connectors);

        var actions = new ActionRegistry(
            List.of(
                foreach,
                new CorrelateAction(store),
                spawn,
                new RunAwaitAction(store),
                new RunWaveAction(store),
                new GateAwaitAction(store),
                new SignalEmitAction(store, this::deliverSignal),
                new IssueCommentAction(trackers),
                issueActions.issueCreateAction(trackers),
                recordingAssign(issueActions.issueAssignAction(trackers)),
                stateActions.stateSetAction(store),
                stateActions.stateVarAction(store),
                stateActions.metricsRecordAction(store),
                stubContainerInit(),
                stubPlanningAgent(),
                // The child workflow has to be loadable for the coordinator to
                // spawn it, and a workflow only loads when every action it names
                // exists — so the real ones are registered alongside the stubs.
                new AgentRunAction(environments, prompts),
                new AgentNewSessionAction(environments),
                new PrConversationAction(vcsClients),
                new RepoContextAction(new RepositoryConfigResolver(vcs), vcs),
                prActions.prCreateAction(vcsClients),
                prActions.prCommentAction(vcsClients),
                prActions.prRequestReviewAction(vcsClients),
                prActions.prReadAction(vcsClients),
                git.gitPullAction(environments),
                git.gitPushAction(environments),
                git.gitStatusAction(environments),
                git.execAction(environments),
                git.agentEnsureCommittedAction(environments),
                git.instanceDestroyAction(environments),
                review.commentReactAction(vcsClients),
                review.prReplyAction(vcsClients),
                review.prIsAssignedAction(vcsClients),
                review.prSetAssigneesAction(vcsClients),
                review.prFindByHeadAction(vcsClients),
                review.prReviewCommentsAction(vcsClients),
                review.prReviewAction(vcsClients),
                review.attachmentsFetchAction(
                    trackers,
                    environments,
                    dev.smithyai.orchestrator.service.design.FigmaClient.inactive(),
                    dev.smithyai.orchestrator.config.FigmaConfig.disabled()
                ),
                review.fileDeleteAction(vcsClients),
                review.fileUrlAction(vcsClients),
                review.repoCloneUrlAction(vcsClients),
                review.prLinkAction(vcsClients),
                review.issueLinkAction(vcsClients),
                ci.ciRetryGuardAction(store, new CiConfig(false)),
                ci.ciResetAction(store),
                issueActions.issueLabelAction(trackers),
                issueActions.issueReadAction(trackers)
            )
        );

        var executor = new StepExecutor(actions, renderer, store);
        setExecutor(foreach, executor);

        var policy = new WorkflowPolicyConfig(null, null, definitions.toString());
        workflows = new WorkflowRegistry(
            new WorkflowDefinitionLoader(new WorkflowDefinitionParser()),
            new CapabilityValidator(actions),
            policy,
            vcs,
            vcs,
            vcs,
            deploymentConfig
        );
        workflows.loadAll();
        // Same construction cycle as the executor above: the registry needs the
        // actions to validate, and spawning needs the registry to seed a child.
        setField(spawn, "workflows", workflows);

        engine = new RunEngine(
            workflows,
            new WorkflowRouter(renderer),
            executor,
            store,
            environments,
            null,
            new EventDebouncer(),
            new RunLocks()
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
        setField(foreach, "executor", executor);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Driving the story ────────────────────────────────────

    private static WorkflowEvent storyAssigned(String source) {
        return new WorkflowEvent.IssueAssigned(
            new IssueContext(
                new RepoInfo("acme", "product", "https://git.invalid/acme/product", source),
                "PROD-1",
                "Search everywhere",
                "Users want search",
                "main",
                "coordinator"
            ),
            null
        );
    }

    private static WorkflowEvent storyAssigned() {
        return new WorkflowEvent.IssueAssigned(
            new IssueContext(STORY_REPO, "PROD-1", "Search everywhere", "Users want search", "main", "coordinator"),
            "https://git.invalid/acme/product"
        );
    }

    private static WorkflowEvent storyApproved() {
        return new WorkflowEvent.PlanApproved(
            new IssueContext(STORY_REPO, "PROD-1", "Search everywhere", "Users want search", "main", "coordinator"),
            "alice"
        );
    }

    private static WorkflowEvent storyApproved(String source) {
        return new WorkflowEvent.PlanApproved(
            new IssueContext(
                new RepoInfo("acme", "product", "https://git.invalid/acme/product", source),
                "PROD-1",
                "Search everywhere",
                "Users want search",
                "main",
                "coordinator"
            ),
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
    void aStoryAndATaskAreToldApartByWhoTheyWereHandedTo() {
        // The same issue, in the same repository, differing only in who it was
        // handed to. Without this both workflows claim it and two agents start.
        var router = new WorkflowRouter(new ExpressionRenderer());
        var definitions = workflows.all();

        var story = router.route(storyAssigned(), definitions);
        assertEquals(
            List.of("acme-coordinator"),
            story.stream().map(WorkflowRouter.Decision::workflowName).toList(),
            "a feature handed to the coordinator"
        );

        var task = new WorkflowEvent.IssueAssigned(
            new IssueContext(STORY_REPO, "PROD-2", "A one-repo change", "", "main", "smithy"),
            null
        );
        assertEquals(
            List.of("smithy-development"),
            router.route(task, definitions).stream().map(WorkflowRouter.Decision::workflowName).toList(),
            "and a task handed to smithy"
        );
    }

    @Test
    void assigningAnExistingTaskToAnotherActorTransfersItsWorkflow() {
        var asTask = new WorkflowEvent.IssueAssigned(
            new IssueContext(STORY_REPO, "PROD-1", "Search everywhere", "Users want search", "main", "smithy"),
            "https://git.invalid/acme/product"
        );
        engine.handle(asTask);
        Run task = store.findByCorrelation(CorrelationKind.ISSUE, "acme/product#PROD-1").orElseThrow();
        assertEquals("smithy-development", task.workflowName());

        engine.handle(storyAssigned());

        assertEquals(RunStatus.CANCELLED, store.find(task.id()).orElseThrow().status());
        assertEquals("acme-coordinator", story().workflowName());
    }

    @Test
    void anIssueRaisedOutsideTheStoryRepositoriesIsNotAStory() {
        // A human assigning an issue directly in a catalog repository is
        // ordinary work. The coordinator claiming it too would plan a feature
        // for a single task and fan out from it.
        var inACatalogRepo = new WorkflowEvent.IssueAssigned(
            new IssueContext(
                new RepoInfo("acme", "api", "https://git.invalid/acme/api"),
                "99",
                "Fix a typo",
                "",
                "main",
                "smithy"
            ),
            null
        );

        var claimed = engine
            .handle(inACatalogRepo)
            .stream()
            .filter(o -> o.handled())
            .toList();

        assertTrue(
            claimed.stream().noneMatch(o -> o.workflowName().equals("acme-coordinator")),
            "the coordinator stayed out of it: " + claimed
        );
    }

    @Test
    void theBuiltInWithNoCatalogClaimsNothing() throws Exception {
        // The shipped coordinator has an empty catalog. Left unguarded it claimed
        // every issue assigned anywhere and raced the workflow that should have
        // handled it — which is exactly what happened the first time this ran
        // against a real repository.
        var bare = Files.createDirectory(tempDir.resolve("bare-workflows"));
        var registry = new WorkflowRegistry(
            new WorkflowDefinitionLoader(new WorkflowDefinitionParser()),
            new CapabilityValidator(new ActionRegistry(List.of())),
            new WorkflowPolicyConfig(null, null, bare.toString()),
            vcs,
            vcs,
            vcs
        );
        registry.loadAll();

        var decisions = new WorkflowRouter(new ExpressionRenderer()).route(storyAssigned(), registry.all());

        assertTrue(
            decisions.stream().noneMatch(d -> d.workflowName().equals("feature-coordinator")),
            "the catalog-less built-in stays out of it: " + decisions
        );
    }

    @Test
    void aCatalogIsWhatMakesItClaimAStory() {
        // And the guard reads the workflow's own vars, which only exist on the
        // definition at routing time — there is no run yet.
        assertEquals(
            1,
            engine
                .handle(storyAssigned())
                .stream()
                .filter(o -> o.handled())
                .count()
        );
    }

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
    void approvalTellsTheStoryWhatWasCreatedAndWhatComesNext() {
        engine.handle(storyAssigned());
        engine.handle(storyApproved());

        // The child issues live in other repositories; without this comment,
        // approval looks like silence from where the feature was asked for.
        String summary = vcs.issueComments.getLast();
        for (var created : vcs.createdIssues) {
            assertTrue(
                summary.contains("%s/%s/issues/%s".formatted(created.owner(), created.repo(), created.issueRef())),
                "no link to " + created.owner() + "/" + created.repo() + " in: " + summary
            );
        }
        assertTrue(summary.contains("What's needed from you"), summary);
    }

    @Test
    void aChildsMergeRequestLinkIsRelayedOntoTheStory() {
        engine.handle(storyAssigned());
        engine.handle(storyApproved());

        var child = store.findChildren(story().id()).getFirst();
        var signal = new SignalEmitAction(store, this::deliverSignal);
        signal.execute(
            new ActionContext(store.find(child.id()).orElseThrow(), storyApproved(), Map.of(), child.vars()),
            Map.of(
                "signal",
                "pr-opened",
                "owner",
                "acme",
                "repo",
                "api",
                "issueRef",
                "1",
                "prNumber",
                100,
                "url",
                "https://git.invalid/acme/api/pulls/100"
            )
        );

        String relayed = vcs.issueComments.getLast();
        assertTrue(relayed.contains("https://git.invalid/acme/api/pulls/100"), relayed);
        assertTrue(relayed.contains("acme/api#1"), relayed);
    }

    @Test
    void everyChildIssueLinksBackToTheStory() {
        engine.handle(storyAssigned());
        engine.handle(storyApproved());

        // The tracker turns this into a link on both issues, which is how a
        // person finds their way from one task to the feature it belongs to.
        for (var created : vcs.createdIssues) {
            assertTrue(
                created.body().contains("Part of acme/product#PROD-1"),
                "no reference back to the story in " + created.repo() + ": " + created.body()
            );
        }
        // And the agent's own text survives above it.
        assertTrue(vcs.createdIssues.getFirst().body().startsWith("GET /search"), vcs.createdIssues.getFirst().body());
    }

    @Test
    void aChildIssueRoutesToItsRunWithNothingWrittenIntoTheIssueBody() {
        engine.handle(storyAssigned());
        engine.handle(storyApproved());

        var created = vcs.createdIssues.getFirst();
        // A human-readable link is fine and wanted; what must not be there is
        // anything the platform reads back to work out parentage.
        assertFalse(created.body().contains("Parent story"), "no marker to be parsed back out");

        var child = store.findByCorrelation(CorrelationKind.ISSUE, "acme/api#" + created.issueRef()).orElseThrow();
        assertEquals("smithy-development", child.workflowName());
        assertEquals(story().id(), child.parentRunId());
    }

    /**
     * A child knows which system it is in, and knows it from configuration.
     *
     * <p>The provider used to be threaded through the plan the agent wrote, so
     * a model that dropped the field left the child labelled with its parent's
     * provider — which for a Jira story fanning out to repositories is never
     * the right answer.
     */
    @Test
    void aChildKnowsItsProviderWithoutThePlanHavingToSayIt() {
        // The plan the agent returns names a source and nothing more; the
        // provider behind it is looked up, not repeated.
        engine.handle(storyAssigned("forgejo-main"));
        engine.handle(storyApproved("forgejo-main"));

        var child = store.findChildren(story().id()).getFirst();

        assertEquals("forgejo-main", child.vars().get(RunEngine.SOURCE_VAR));
        assertEquals("forgejo", child.vars().get(RunEngine.SOURCE_PROVIDER_VAR));
    }

    @Test
    void anActionAnswersTheSystemItsEventCameFrom() {
        var jira = new StubVcsClient();
        var gitlab = new StubVcsClient();
        var trackers = new dev.smithyai.orchestrator.service.vcs.IssueTrackers(
            java.util.Map.of("jira", jira, "gitlab", gitlab)
        );
        var comment = new IssueCommentAction(trackers);

        // A story raised in Jira.
        comment.execute(
            new ActionContext(null, storyAssigned("jira"), Map.of(), Map.of()),
            Map.of("owner", "PROJ", "repo", "PROJ", "issue", "PROJ-1", "body", "on it")
        );
        // A child issue raised in GitLab, same event type, nothing in the
        // definition saying which.
        comment.execute(
            new ActionContext(null, storyAssigned("gitlab"), Map.of(), Map.of()),
            Map.of("owner", "acme", "repo", "api", "issue", "7", "body", "on it")
        );

        assertEquals(1, jira.issueComments.size(), "the Jira story was answered in Jira");
        assertEquals(1, gitlab.issueComments.size(), "and the GitLab issue in GitLab");
    }

    @Test
    void anExplicitConnectorOverridesTheEventsOwn() {
        var jira = new StubVcsClient();
        var gitlab = new StubVcsClient();
        var trackers = new dev.smithyai.orchestrator.service.vcs.IssueTrackers(
            java.util.Map.of("jira", jira, "gitlab", gitlab)
        );

        // What a coordinator does: the story arrived from Jira, the child issue
        // belongs somewhere else.
        new IssueActions()
            .issueCreateAction(trackers)
            .execute(
                new ActionContext(null, storyAssigned("jira"), Map.of(), Map.of()),
                Map.of("target", "gitlab", "owner", "acme", "repo", "api", "title", "Search endpoint")
            );

        assertEquals(1, gitlab.createdIssues.size(), "created where the work lives");
        assertTrue(jira.createdIssues.isEmpty(), "not where the story lives");
    }

    @Test
    void eachWorkflowActsAsItsOwnIdentity() {
        var asSmithy = new StubVcsClient();
        var asCoordinator = new StubVcsClient();
        var trackers = new dev.smithyai.orchestrator.service.vcs.IssueTrackers(
            Map.of(
                "smithy",
                Map.<String, dev.smithyai.orchestrator.service.vcs.IssueTrackerClient>of("forgejo", asSmithy),
                "coordinator",
                Map.<String, dev.smithyai.orchestrator.service.vcs.IssueTrackerClient>of("forgejo", asCoordinator)
            ),
            "smithy"
        );
        var comment = new IssueCommentAction(trackers);
        var input = Map.<String, Object>of("owner", "acme", "repo", "product", "issue", "1", "body", "planned");

        comment.execute(new ActionContext(null, storyAssigned("forgejo"), Map.of(), Map.of(), "coordinator"), input);

        // A reader of the story has to be able to tell who wrote this, and a
        // bot answering itself is how comment loops start.
        assertEquals(1, asCoordinator.issueComments.size(), "the coordinator signed its own plan");
        assertTrue(asSmithy.issueComments.isEmpty(), "not the agent that will do the work");
    }

    @Test
    void aSignalIsAnsweredWhereTheStoryLivesRatherThanWhereTheWorkDoes() {
        var jira = new StubVcsClient();
        var gitlab = new StubVcsClient();
        var trackers = new dev.smithyai.orchestrator.service.vcs.IssueTrackers(
            java.util.Map.of("gitlab", gitlab, "jira", jira)
        );

        // A child finished, so the event this transition sees is a signal from
        // the child's own system. The comment belongs on the story.
        var childDone = new WorkflowEvent.Signal(
            new dev.smithyai.orchestrator.model.RepoInfo("acme", "api", null, "gitlab"),
            "child-done",
            Map.of()
        );
        new IssueCommentAction(trackers).execute(
            new ActionContext(null, childDone, Map.of(), Map.of("storySource", "jira")),
            Map.of("target", "jira", "owner", "PROJ", "repo", "PROJ", "issue", "PROJ-1", "body", "next wave")
        );

        assertEquals(1, jira.issueComments.size(), "the story was told, in the system it lives in");
        assertTrue(gitlab.issueComments.isEmpty());
    }

    @Test
    void aStoryWithNoRepositoryBehindItMountsNothingRatherThanCloningNull() {
        var renderer = new ExpressionRenderer();
        // Jira scopes a story to its project; there is no repository behind it.
        var story = new WorkflowEvent.IssueAssigned(
            new IssueContext(new RepoInfo("PROJ", "PROJ", null, "jira"), "PROJ-1", "Search", "", null, "coordinator"),
            null
        );
        var rendered = renderer.renderInputs(
            Map.of("extraRepos", java.util.List.of(Map.of("cloneUrl", "{{ repo.cloneUrl }}", "path", "/story"))),
            new ActionContext(null, story, Map.of(), Map.of())
        );

        @SuppressWarnings("unchecked")
        var entries = (java.util.List<Map<String, Object>>) rendered.get("extraRepos");
        assertEquals("", entries.getFirst().get("cloneUrl"), "empty, not the text \"null\"");
    }

    @Test
    void aMisspelledConnectorFailsRatherThanPostingSomewhereElse() {
        var jira = new StubVcsClient();
        var gitlab = new StubVcsClient();
        var trackers = new dev.smithyai.orchestrator.service.vcs.IssueTrackers(
            java.util.Map.of("jira", jira, "gitlab", gitlab)
        );
        var create = new IssueActions().issueCreateAction(trackers);
        var context = new ActionContext(null, storyAssigned("jira"), Map.of(), Map.of());
        var input = Map.<String, Object>of("target", "gitlba", "owner", "acme", "repo", "api", "title", "x");

        // Silently using the fallback would file this in Jira, which is a real
        // issue in someone else's tracker.
        var refused = assertThrows(IllegalArgumentException.class, () -> create.execute(context, input));
        assertTrue(refused.getMessage().contains("gitlba"), refused.getMessage());
        assertTrue(refused.getMessage().contains("gitlab"), "and says what is available: " + refused.getMessage());
        assertTrue(jira.createdIssues.isEmpty());
        assertTrue(gitlab.createdIssues.isEmpty());
    }

    @Test
    void aSpawnedChildStartsWithItsOwnWorkflowsVariables() {
        engine.handle(storyAssigned());
        engine.handle(storyApproved());

        var child = store.findChildren(story().id()).getFirst();
        // Seeded from smithy-development's own vars, which a run started by an
        // event gets from the engine. Without them the child's first step asks
        // for a branch prefix nobody gave it.
        assertEquals("smithy/", child.vars().get("branchPrefix"));
        assertEquals(".smithy/plans", child.vars().get("planDir"));
        // And what the coordinator handed it still wins.
        assertEquals("api", child.vars().get("repo"));
    }

    @Test
    void aChildIssueAssignmentAdoptsTheSpawnedRunRatherThanStartingAnother() {
        engine.handle(storyAssigned());
        engine.handle(storyApproved());
        var child = store.findChildren(story().id()).getFirst();
        int runsBefore = store.findRecent(50).size();

        // The webhook for the issue the coordinator just created.
        var assigned = new WorkflowEvent.IssueAssigned(
            new IssueContext(
                new RepoInfo("acme", "api", "https://git.invalid/acme/api"),
                String.valueOf(child.vars().get("issueRef")),
                "Search endpoint",
                "GET /search",
                "main",
                "smithy"
            ),
            null
        );
        engine.handle(assigned);

        assertEquals(runsBefore, store.findRecent(50).size(), "no second run was opened for the child issue");
    }

    @Test
    void aChildFinishingIsAnnouncedByTheEngineNotTheChildWorkflow() {
        engine.handle(storyAssigned());
        engine.handle(storyApproved());
        var child = store.findChildren(story().id()).getFirst();

        // Terminal by the ordinary route — smithy-development's destroy rule —
        // with no signal.emit anywhere in the child's definition.
        engine.handle(
            new WorkflowEvent.IssueUnassigned(
                new IssueContext(
                    new RepoInfo("acme", "api", "https://git.invalid/acme/api"),
                    String.valueOf(child.vars().get("issueRef")),
                    "Search endpoint",
                    "GET /search",
                    "main"
                )
            )
        );

        var parentEvents = store.findEvents(story().id()).stream().map(RunEvent::type).toList();
        assertTrue(parentEvents.contains("child.finished"), "parent was told: " + parentEvents);
    }

    @Test
    void onlyTheFirstWaveIsAssigned() {
        engine.handle(storyAssigned());
        engine.handle(storyApproved());

        // The web client depends on the API, so it is created but not started.
        assertEquals(1, assignments.size());
        assertEquals("api", assignments.getFirst().get("repo"));
        assertEquals(
            List.of("smithy"),
            assignments.getFirst().get("actors"),
            "the logical actor assigned to the child"
        );
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
