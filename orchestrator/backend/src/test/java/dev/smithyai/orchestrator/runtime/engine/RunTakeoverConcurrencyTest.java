package dev.smithyai.orchestrator.runtime.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.smithyai.orchestrator.config.AgentConfig.ClaudeAgentConfig;
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
    private RunTakeover takeover;

    @BeforeEach
    void setUp() {
        store = mock(RunStore.class);
        environments = mock(RunEnvironments.class);
        locks = new RunLocks();

        when(store.isLeased(any())).thenReturn(true);
        when(store.acquireLease(any(), any(), any())).thenReturn(Optional.of(Instant.now().plusSeconds(30)));

        takeover = new RunTakeover(store, environments, locks, agentConfig("5m"));
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

        new RunTakeover(store, environments, locks, agentConfig(null)).send(RUN, "hello", List.of());

        verify(agent).setTurnTimeout(Duration.ofMinutes(5));
    }

    private static ClaudeAgentConfig agentConfig(String takeoverTimeout) {
        return new ClaudeAgentConfig("claude-opus-5", null, null, null, takeoverTimeout);
    }
}
