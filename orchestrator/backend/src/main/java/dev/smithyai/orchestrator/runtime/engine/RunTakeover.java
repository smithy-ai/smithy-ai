package dev.smithyai.orchestrator.runtime.engine;

import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.runtime.store.Run;
import dev.smithyai.orchestrator.runtime.store.RunLease;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * A human driving a run's agent session directly.
 *
 * <p>Sometimes the fastest way through is to talk to the agent yourself. While
 * someone holds a run, inbound events are not handled — an agent acting on a
 * webhook on top of what a person is typing into the same session produces
 * work neither of them asked for.
 *
 * <p>Control is a heartbeat, not a flag. Someone who takes over and then closes
 * the tab must not leave the run frozen, so it lapses on its own unless the
 * dashboard keeps renewing it.
 */
@Slf4j
@Component
public class RunTakeover {

    private static final Duration TTL = Duration.ofSeconds(30);

    private final RunStore store;
    private final RunEnvironments environments;

    public RunTakeover(RunStore store, RunEnvironments environments) {
        this.store = store;
        this.environments = environments;
    }

    public boolean isHeld(String runId) {
        return store.isLeased(runId);
    }

    /** Take control, or renew it. Empty if someone else already has it. */
    public Optional<Instant> heartbeat(String runId) {
        boolean fresh = !store.isLeased(runId);
        var expiry = store.acquireLease(runId, RunLease.HUMAN, TTL);
        if (fresh && expiry.isPresent()) {
            store.appendEvent(runId, "takeover.started", null);
            log.info("A human took over run {}", runId);
        }
        return expiry;
    }

    public void release(String runId) {
        if (store.isLeased(runId)) {
            store.appendEvent(runId, "takeover.released", null);
            log.info("Human takeover released on run {}", runId);
        }
        store.releaseLease(runId);
    }

    /**
     * Send a message into the run's agent session and return the reply.
     *
     * <p>Renews the lease first: a long exchange should not lapse halfway
     * through because the reply took longer than the heartbeat interval.
     */
    public String send(Run run, String text, List<String> tools) {
        heartbeat(run.id());
        var container = environments.container(run);
        var agent = environments.agent(run, tools);
        String reply = agent.send(text);
        environments.rememberAgentSession(container, agent);
        store.appendEvent(run.id(), "takeover.message", Map.of("length", text.length()));
        return reply;
    }
}
