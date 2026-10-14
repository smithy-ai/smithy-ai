package dev.smithyai.orchestrator.runtime.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.smithyai.orchestrator.config.AgentConfig.ClaudeAgentConfig;
import dev.smithyai.orchestrator.model.IssueContext;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.runtime.store.Run;
import dev.smithyai.orchestrator.runtime.store.RunStatus;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import dev.smithyai.orchestrator.service.claude.ClaudeSession;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * A human message must not land on a session that is already mid-turn.
 *
 * <p>Holding the takeover lease is not the same as the session being free: the
 * lease stops new events being dispatched, while a turn that was already running
 * when someone took over keeps running. Firing a second {@code claude --resume}
 * at that session produced a message that never landed and a request that hung
 * until the turn budget ran out, which is what this pins down.
 */
class RunTakeoverConcurrencyTest {

    private static final Run RUN = new Run(
        "run-1",
        "smithy",
        "1",
        RunStatus.RUNNING,
        "building",
        Map.of(),
        null,
        null,
        Instant.now(),
        Instant.now(),
        null
    );

    private RunStore store;
    private RunEnvironments environments;
    private RunLocks locks;
    private RunEngine engine;
    private RunTakeover takeover;

    @BeforeEach
    void setUp() {
        store = mock(RunStore.class);
        environments = mock(RunEnvironments.class);
        locks = new RunLocks();
        engine = mock(RunEngine.class);
        when(engine.drainHeld(any())).thenReturn(List.of());

        when(store.isLeased(any())).thenReturn(true);
        when(store.acquireLease(any(), any(), any())).thenReturn(Optional.of(Instant.now().plusSeconds(30)));

        takeover = new RunTakeover(store, environments, locks, agentConfig("5m"), engine);
    }

    private static WorkflowEvent approval() {
        var repo = new RepoInfo("acme", "platform", null, "jira-product", "jira");
        return new WorkflowEvent.PlanApproved(new IssueContext(repo, "ECD-9", "Add search", "body", "main", ""), "");
    }

    /**
     * The point of finishing rather than releasing: the workflow writes the
     * agreed plan down before a held approval executes it, so the two must
     * reach the engine in that order on one thread.
     */
    @Test
    void finishingDeliversTheSignalBeforeReplayingWhatWasHeld() {
        var held = approval();
        when(engine.drainHeld(RUN.id())).thenReturn(List.of(held));
        when(engine.deliver(any(), any())).thenReturn(true);

        takeover.finish(RUN);

        InOrder inOrder = inOrder(engine);
        var signal = ArgumentCaptor.forClass(WorkflowEvent.Signal.class);
        inOrder.verify(engine, timeout(5000)).deliver(eq(RUN.id()), signal.capture());
        inOrder.verify(engine, timeout(5000)).redeliverAll(RUN.id(), List.of(held));

        assertEquals("signal:" + RunTakeover.FINISHED, signal.getValue().name());
        // A second hand-back in the same state must be its own transition.
        assertFalse(signal.getValue().identity().isBlank());
        assertNotEquals("null", signal.getValue().identity());
        // The lease is gone before the signal lands, or the engine would hold it too.
        verify(store, timeout(5000)).releaseLease(RUN.id());
        verify(store).appendEvent(eq(RUN.id()), eq("takeover.finished"), any());
        assertEquals(0, signal.getValue().payload().get("messages"));
    }

    /**
     * An approval given on the strength of a conversation must not execute the
     * plan from before it: when the workflow fails on the hand-back signal, what
     * was held is recorded, not replayed.
     */
    @Test
    void aFailedHandBackTransitionKeepsHeldEventsFromRunningTheOldPlan() {
        var held = approval();
        when(engine.drainHeld(RUN.id())).thenReturn(List.of(held));
        when(engine.deliver(any(), any())).thenThrow(new IllegalStateException("turn timed out"));

        takeover.finish(RUN);

        verify(store, timeout(5000)).appendEvent(eq(RUN.id()), eq("takeover.replay_skipped"), any());
        verify(engine, never()).redeliverAll(any(), any());
    }

    @Test
    void aReleaseWhileAHandBackIsQueuedIsANoOp() throws Exception {
        var gate = new CountDownLatch(1);
        when(engine.deliver(any(), any())).thenAnswer(invocation -> {
            gate.await(5, TimeUnit.SECONDS);
            return true;
        });

        takeover.finish(RUN);
        // The dashboard's cleanup DELETE lands while the hand-back is still on its way.
        takeover.release(RUN.id());
        gate.countDown();

        verify(engine, timeout(5000)).deliver(any(), any());
        verify(store, timeout(5000)).appendEvent(eq(RUN.id()), eq("takeover.finished"), any());
        verify(store, never()).appendEvent(eq(RUN.id()), eq("takeover.released"), any());
    }

    /** After a restart the process remembers no holder, but the held events are still there. */
    @Test
    void theSweepReplaysHeldEventsOfARunNobodyHoldsAnyMore() {
        var held = approval();
        when(store.isLeased(RUN.id())).thenReturn(false);
        when(engine.heldRunIds()).thenReturn(java.util.Set.of(RUN.id()));
        when(engine.hasHeld(RUN.id())).thenReturn(true);
        when(engine.drainHeld(RUN.id())).thenReturn(List.of(held));

        takeover.sweepLapsed();

        verify(engine, timeout(5000)).redeliverAll(RUN.id(), List.of(held));
        var payload = ArgumentCaptor.forClass(Map.class);
        verify(store).appendEvent(eq(RUN.id()), eq("takeover.released"), payload.capture());
        assertEquals("lapse", payload.getValue().get("via"));
    }

    @Test
    void releasingReplaysWhatWasHeldWithoutAskingAnythingOfTheWorkflow() {
        var held = approval();
        when(engine.drainHeld(RUN.id())).thenReturn(List.of(held));

        takeover.release(RUN.id());

        verify(engine, timeout(5000)).redeliverAll(RUN.id(), List.of(held));
        verify(engine, never()).deliver(any(), any());
        verify(store).appendEvent(eq(RUN.id()), eq("takeover.released"), any());
    }

    @Test
    void lettingGoOfARunNobodyHeldIsANoOp() {
        when(store.isLeased(any())).thenReturn(false);

        takeover.release(RUN.id());

        verify(store, never()).appendEvent(any(), eq("takeover.released"), any());
        verify(engine, never()).redeliverAll(any(), any());
    }

    @Test
    void aLapsedLeaseIsReleasedByTheSweep() {
        // Fresh takeover: nothing leased yet, so the heartbeat records a start.
        when(store.isLeased(RUN.id())).thenReturn(false);
        takeover.heartbeat(RUN.id());
        verify(store).appendEvent(RUN.id(), "takeover.started", null);

        // The tab closed; the lease ran out without a goodbye.
        takeover.sweepLapsed();

        var payload = ArgumentCaptor.forClass(Map.class);
        verify(store).appendEvent(eq(RUN.id()), eq("takeover.released"), payload.capture());
        assertEquals("lapse", payload.getValue().get("via"));
        // And only once: a second sweep finds nothing to let go of.
        takeover.sweepLapsed();
        verify(store, times(1)).appendEvent(eq(RUN.id()), eq("takeover.released"), any());
    }

    @Test
    void aMessageSentWhileATurnIsInFlightIsRefusedRatherThanQueued() throws Exception {
        var turnStarted = new CountDownLatch(1);
        var releaseTurn = new CountDownLatch(1);

        // Stand in for an engine turn: it holds the run's lock and works.
        var engineTurn = Thread.ofPlatform().start(() ->
            locks.inRun(RUN.id(), () -> {
                turnStarted.countDown();
                try {
                    releaseTurn.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            })
        );

        assertTrue(turnStarted.await(5, TimeUnit.SECONDS), "the stand-in turn never started");
        try {
            long began = System.nanoTime();
            var thrown = assertThrows(AgentBusyException.class, () -> takeover.send(RUN, "hello", List.of()));
            var waited = Duration.ofNanos(System.nanoTime() - began);

            assertTrue(thrown.getMessage().contains("run-1"), thrown.getMessage());
            // The point is that it gave up quickly rather than blocking on the turn.
            assertTrue(waited.toSeconds() < 8, "waited " + waited + " before refusing");
            // And no second session was ever opened on the container.
            verify(environments, never()).agent(any(), any());
        } finally {
            releaseTurn.countDown();
            engineTurn.join(5000);
        }
    }

    @Test
    void aMessageOnAFreeSessionGoesThroughOnTheTakeoverBudget() {
        var container = mock(ContainerSession.class);
        var agent = mock(ClaudeSession.class);
        when(environments.container(any())).thenReturn(container);
        when(environments.agent(any(), any())).thenReturn(agent);
        when(agent.send(anyString())).thenReturn("done");

        assertEquals("done", takeover.send(RUN, "hello", List.of()));

        // A person waiting in a browser gets the takeover budget, not a build
        // turn's — an unanswered request is a hung dashboard, not a long job.
        verify(agent).setTurnTimeout(Duration.ofMinutes(5));
        verify(environments).rememberAgentSession(container, agent);
    }

    @Test
    void theTurnLockIsReleasedWhenATurnFails() {
        var container = mock(ContainerSession.class);
        var agent = mock(ClaudeSession.class);
        when(environments.container(any())).thenReturn(container);
        when(environments.agent(any(), any())).thenReturn(agent);
        when(agent.send(anyString())).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> takeover.send(RUN, "hello", List.of()));

        // A lock a failed turn kept would wedge the run for good.
        assertFalse(locks.isBusy(RUN.id()));
    }

    @Test
    void anUnsetTakeoverTimeoutFallsBackToTheBuiltInDefault() {
        var container = mock(ContainerSession.class);
        var agent = mock(ClaudeSession.class);
        when(environments.container(any())).thenReturn(container);
        when(environments.agent(any(), any())).thenReturn(agent);
        when(agent.send(anyString())).thenReturn("done");

        new RunTakeover(store, environments, locks, agentConfig(null), engine).send(RUN, "hello", List.of());

        verify(agent).setTurnTimeout(Duration.ofMinutes(5));
    }

    @Test
    void aTakeoverTurnGetsARealToolListRatherThanNone() {
        var container = mock(ContainerSession.class);
        var agent = mock(ClaudeSession.class);
        when(environments.container(any())).thenReturn(container);
        when(environments.agent(any(), any())).thenReturn(agent);
        when(agent.send(anyString())).thenReturn("done");

        // What the dashboard sends: it does not decide the tools.
        takeover.send(RUN, "push the branch", null);

        // An empty list makes ClaudeSession omit --allowedTools, and a headless
        // turn on default permissions then refuses every tool call before running
        // it — the agent cannot read a file, let alone push a branch.
        var tools = ArgumentCaptor.forClass(List.class);
        verify(environments).agent(any(), tools.capture());
        assertFalse(tools.getValue().isEmpty(), "the turn was given no tools");
        assertTrue(tools.getValue().contains("Bash"), "Bash missing: " + tools.getValue());
        assertTrue(tools.getValue().contains("Read"), "Read missing: " + tools.getValue());
    }

    @Test
    void anExplicitToolListStillWins() {
        var container = mock(ContainerSession.class);
        var agent = mock(ClaudeSession.class);
        when(environments.container(any())).thenReturn(container);
        when(environments.agent(any(), any())).thenReturn(agent);
        when(agent.send(anyString())).thenReturn("done");

        takeover.send(RUN, "read only please", List.of("Read"));

        var tools = ArgumentCaptor.forClass(List.class);
        verify(environments).agent(any(), tools.capture());
        assertEquals(List.of("Read"), tools.getValue());
    }

    private static ClaudeAgentConfig agentConfig(String takeoverTimeout) {
        return new ClaudeAgentConfig("claude-opus-5", null, null, null, takeoverTimeout, null);
    }
}
