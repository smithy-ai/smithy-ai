package dev.smithyai.orchestrator.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.WorkflowPolicyConfig;
import dev.smithyai.orchestrator.model.IssueContext;
import dev.smithyai.orchestrator.model.PrContext;
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
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A definition actually running.
 *
 * <p>The previous attempt at this shipped a parser and a validator that nothing
 * injected — a definition could be written and checked but never executed. These
 * tests drive real YAML through routing, state transitions and steps.
 */
class RunEngineTest {

    private static final RepoInfo REPO = new RepoInfo(
        "acme",
        "platform",
        "https://git.invalid/acme/platform",
        "forgejo-main",
        "forgejo"
    );

    private static final String COORDINATOR = """
        apiVersion: smithy.ai/v1alpha1
        kind: Workflow
        metadata:
          name: feature-coordinator
        vars:
          maxReviewRounds: 3
        routing:
          - event: issue.assigned
            when: "{{ repo.fullName == 'acme/platform' }}"
            action: create
            key: "{{ repo.fullName }}#{{ event.issueRef }}"
          - event: [issue.plan_approved, issue.commented]
            action: dispatch
            key: "{{ repo.fullName }}#{{ event.issueRef }}"
          - event: [pr.merged, issue.unassigned]
            action: destroy
            key: "{{ repo.fullName }}#{{ event.issueRef }}"
          - event: pr.commented
            action: dispatch
            by: pr
        state:
          initial: planning
          terminal: done
          planning:
            on:
              issue.assigned:
                steps:
                  - uses: state.var
                    with:
                      greeting: "planning {{ event.issueRef }}"
                  - uses: gate.await
                    id: approval
                    with:
                      key: plan-approval
              issue.plan_approved:
                to: executing
                steps:
                  - uses: metrics.record
                    with:
                      name: plan_approved
              pr.commented:
                steps:
                  - uses: state.var
                    with:
                      lastPrComment: "{{ event.commentBody }}"
          executing:
            on:
              issue.commented:
                to: done
                debounce: 150ms
                steps:
                  - uses: state.var
                    with:
                      handledComments: "{{ event.batchSize }}"
                  - uses: metrics.record
                    with:
                      name: work_finished
          done:
            on: {}
        """;

    @TempDir
    Path tempDir;

    private RunStore store;
    private RunEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("runs.db") + "?foreign_keys=on");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        store = new RunStore(JdbcClient.create((DataSource) dataSource), new ObjectMapper());

        var definitions = Files.createDirectory(tempDir.resolve("workflows"));
        Files.writeString(definitions.resolve("feature-coordinator.yml"), COORDINATOR);

        var renderer = new ExpressionRenderer();
        var state = new StateActions();
        // Only the store-backed actions: this definition never touches a
        // container, which is the point — a coordinator does not need one.
        var actions = new ActionRegistry(
            List.of(
                new CorrelateAction(store),
                new RunSpawnAction(store, null),
                new RunAwaitAction(store),
                new GateAwaitAction(store),
                new SignalEmitAction(store, null),
                state.stateSetAction(store),
                state.stateVarAction(store),
                state.metricsRecordAction(store)
            )
        );

        var stubs = new StubVcsClient();
        var policy = new WorkflowPolicyConfig(null, null, definitions.toString());
        var workflows = new WorkflowRegistry(
            new WorkflowDefinitionLoader(new WorkflowDefinitionParser()),
            new CapabilityValidator(actions),
            policy,
            stubs,
            stubs,
            stubs
        );
        workflows.loadAll();

        engine = new RunEngine(
            workflows,
            new WorkflowRouter(renderer),
            new StepExecutor(actions, renderer, store),
            store,
            new RunEnvironments(store, null, null),
            null,
            new EventDebouncer(),
            new RunLocks()
        );
    }

    private static WorkflowEvent assigned() {
        return new WorkflowEvent.IssueAssigned(
            new IssueContext(REPO, "ECD-9", "Add search", "body", "main", "smithy"),
            null
        );
    }

    private static WorkflowEvent approved() {
        return new WorkflowEvent.PlanApproved(
            new IssueContext(REPO, "ECD-9", "Add search", "body", "main", "smithy"),
            "alice"
        );
    }

    private static WorkflowEvent unassigned() {
        return new WorkflowEvent.IssueUnassigned(new IssueContext(REPO, "ECD-9", "Add search", "body", "main", ""));
    }

    private static WorkflowEvent commented() {
        return new WorkflowEvent.IssueComment(
            new IssueContext(REPO, "ECD-9", "Add search", "body", "main", "smithy"),
            "looks good"
        );
    }

    /**
     * Observed live: Jira delivering the same assignment twice, two threads
     * each concluding "no run yet", and two runs — with two same-named
     * containers, the second recreating the first's and killing the agent turn
     * inside it. Creation is resolved under a per-key lock now, so however many
     * identical deliveries race, exactly one run exists afterwards.
     */
    @Test
    void simultaneousIdenticalDeliveriesCreateExactlyOneRun() throws Exception {
        int deliveries = 8;
        var barrier = new java.util.concurrent.CyclicBarrier(deliveries);
        var threads = new java.util.ArrayList<Thread>();
        for (int i = 0; i < deliveries; i++) {
            threads.add(
                Thread.ofVirtual().start(() -> {
                    try {
                        barrier.await();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    engine.handle(assigned());
                })
            );
        }
        for (var thread : threads) thread.join();

        assertEquals(1, store.findRecent(50).size(), "one run, however many times the webhook was delivered");
    }

    @Test
    void anEventStartsARunAndItsStepsExecute() {
        var outcomes = engine.handle(assigned());

        assertEquals(1, outcomes.size());
        var outcome = outcomes.getFirst();
        assertTrue(outcome.handled());
        assertEquals("planning", outcome.toState());

        var run = store.find(outcome.runId()).orElseThrow();
        assertEquals("planning ECD-9", run.vars().get("greeting"), "the step wrote to the run");
        assertEquals(3, run.vars().get("maxReviewRounds"), "and the workflow's own vars seeded it");
        assertEquals("forgejo-main", run.vars().get(RunEngine.SOURCE_VAR));
        assertEquals("forgejo", run.vars().get(RunEngine.SOURCE_PROVIDER_VAR));
        assertEquals(1, store.findPendingWaits(run.id()).size(), "and the gate is armed");
    }

    @Test
    void aSecondEventFindsTheSameRunRatherThanStartingAnother() {
        engine.handle(assigned());
        engine.handle(assigned());

        assertEquals(1, store.findRecent(10).size());
    }

    @Test
    void aTransitionMovesTheRunToItsNextState() {
        engine.handle(assigned());
        var outcome = engine.handle(approved()).getFirst();

        assertEquals("planning", outcome.fromState());
        assertEquals("executing", outcome.toState());
        assertEquals("executing", store.find(outcome.runId()).orElseThrow().state());
    }

    @Test
    void reachingTheTerminalStateCompletesTheRun() throws Exception {
        engine.handle(assigned());
        var started = engine.handle(approved()).getFirst();
        engine.handle(commented());
        settleBatch();

        var run = store.find(started.runId()).orElseThrow();
        assertEquals("done", run.state());
        assertEquals(RunStatus.COMPLETED, run.status());
    }

    @Test
    void aBurstOfTheSameEventBecomesOneTransition() throws Exception {
        engine.handle(assigned());
        var started = engine.handle(approved()).getFirst();

        // Three comments land inside the window — a reviewer's burst. Handling
        // each on its own would mean three agent turns and three commits.
        engine.handle(commented());
        engine.handle(commented());
        engine.handle(commented());
        settleBatch();

        var run = store.find(started.runId()).orElseThrow();
        assertEquals(3, run.vars().get("handledComments"), "the steps saw the whole burst");
        var finished = store
            .findEvents(run.id())
            .stream()
            .filter(event -> "work_finished".equals(event.type()))
            .count();
        assertEquals(1, finished, "and ran once");
    }

    /** Wait out the definition's debounce window. */
    private static void settleBatch() throws InterruptedException {
        Thread.sleep(400);
    }

    @Test
    void anEventTheCurrentStateDoesNotHandleIsIgnored() {
        var started = engine.handle(assigned()).getFirst();

        // 'planning' has no rule for issue.commented, so nothing happens and the
        // run stays where it was rather than erroring.
        var outcome = engine.handle(commented()).getFirst();

        assertFalse(outcome.handled());
        assertEquals("planning", store.find(started.runId()).orElseThrow().state());
    }

    @Test
    void anEventForWorkThisWorkflowNeverStartedIsIgnored() {
        var outcomes = engine.handle(approved());

        assertEquals(1, outcomes.size());
        assertFalse(outcomes.getFirst().handled());
        assertTrue(store.findRecent(10).isEmpty(), "and no run is invented for it");
    }

    @Test
    void everyStepAndEventLandsInTheRunsHistory() {
        var outcome = engine.handle(assigned()).getFirst();
        engine.handle(approved());

        var types = store.findEvents(outcome.runId()).stream().map(RunEvent::type).toList();
        assertEquals(List.of("issue.assigned", "issue.plan_approved", "plan_approved"), types);
    }

    @Test
    void aDefinitionThatDropsAStateStrandsItsRunVisiblyRatherThanSilently() {
        var outcome = engine.handle(assigned()).getFirst();
        store.updateState(outcome.runId(), "a-state-nobody-defines");

        var second = engine.handle(approved()).getFirst();
        // A second event must not repeat the complaint.
        engine.handle(approved());

        assertFalse(second.handled());
        var types = store.findEvents(outcome.runId()).stream().map(RunEvent::type).toList();
        assertEquals(1, types.stream().filter("state.undefined"::equals).count(), "recorded once: " + types);
        assertEquals(RunStatus.WAITING, store.find(outcome.runId()).orElseThrow().status(), "and the run holds");
    }

    @Test
    void aReopenedRunLeavesThePreviousLifesChildrenBehind() {
        var started = engine.handle(assigned()).getFirst();
        var child = store.create("smithy-development", "1", "new", started.runId());
        store.updateStatus(child.id(), RunStatus.COMPLETED);
        assertEquals(1, store.findChildren(started.runId()).size());

        engine.handle(unassigned());
        engine.handle(assigned());

        // Observed live: a story reset over several rounds dragged seventeen
        // dead children along, and run.await counted them — "all children
        // finished" was true the moment the first new child reported in.
        assertTrue(
            store.findChildren(started.runId()).isEmpty(),
            "children of the cancelled life must not count in the new one"
        );
        var orphan = store.find(child.id()).orElseThrow();
        assertNull(orphan.parentRunId(), "the old child keeps its history, just not its parent");
    }

    @Test
    void workTakenOffTheAgentAndHandedBackPicksUpWhereItStarted() {
        var started = engine.handle(assigned()).getFirst();
        engine.handle(unassigned());
        assertEquals(RunStatus.CANCELLED, store.find(started.runId()).orElseThrow().status());

        // Assigning it again is how a person hands the work back.
        var again = engine.handle(assigned()).getFirst();

        assertTrue(again.handled(), "the run took the event rather than ignoring it");
        assertEquals(started.runId(), again.runId(), "and it is the same run, so a parent still counts it");
        var run = store.find(started.runId()).orElseThrow();
        assertEquals("planning", run.state());
        assertFalse(run.isTerminal());
        assertEquals(
            1,
            store
                .findEvents(run.id())
                .stream()
                .filter(e -> e.type().equals("run.reopened"))
                .count()
        );
    }

    @Test
    void aReopenedRunDoesNotSkipTheStepsTheStoppedOneRecorded() {
        var started = engine.handle(assigned()).getFirst();
        // The gate armed by the first attempt, and the step outputs behind it.
        assertEquals(1, store.findPendingWaits(started.runId()).size());
        engine.handle(unassigned());

        engine.handle(assigned());

        // The gate is armed again, which only happens if the step ran rather
        // than being skipped as one this transition already completed.
        assertEquals(1, store.findPendingWaits(started.runId()).size());
    }

    @Test
    void workThatWasDeliveredIsNotStartedOverByAStrayAssignment() {
        var started = engine.handle(assigned()).getFirst();
        store.updateStatus(started.runId(), RunStatus.COMPLETED);

        var again = engine.handle(assigned()).getFirst();

        assertFalse(again.handled());
        assertEquals(RunStatus.COMPLETED, store.find(started.runId()).orElseThrow().status());
    }

    @Test
    void aCommentOnSomeoneElsesPrFromTheSameBranchDoesNotReachTheRun() {
        var started = engine.handle(assigned()).getFirst();
        // What the run registers when it opens its own pull request.
        store.correlate(CorrelationKind.PR, RunRecorder.prRef("acme", "platform", 41), started.runId());
        store.correlate(CorrelationKind.BRANCH, RunRecorder.branchRef("acme", "platform", "ecd-9"), started.runId());

        // A human opened MR 42 from the run's work branch. Its comments are
        // theirs: the run must not answer them just because the branch matches.
        var strangers = engine.handle(prComment(42, "please ignore this MR")).getFirst();
        assertFalse(strangers.handled());
        assertNull(store.find(started.runId()).orElseThrow().vars().get("lastPrComment"));

        // The run's own MR still reaches it, through the PR it registered.
        var own = engine.handle(prComment(41, "looks good")).getFirst();
        assertTrue(own.handled());
        assertEquals("looks good", store.find(started.runId()).orElseThrow().vars().get("lastPrComment"));
    }

    private static WorkflowEvent prComment(int number, String body) {
        return new WorkflowEvent.PrConversationComment(
            new PrContext(REPO, number, "ecd-9: a change", "", false, "ecd-9", "main"),
            "b.human",
            body,
            number * 100L,
            "d" + number
        );
    }

    @Test
    void aRunRemembersWhichConnectorItCameThrough() {
        var jira = new WorkflowEvent.IssueAssigned(
            new IssueContext(
                new RepoInfo("acme", "platform", null, "jira"),
                "ECD-9",
                "Add search",
                "body",
                "main",
                "smithy"
            ),
            null
        );

        var outcome = engine.handle(jira).getFirst();

        assertEquals("jira", store.find(outcome.runId()).orElseThrow().vars().get("source"));
    }
}
