package dev.smithyai.orchestrator.runtime.engine;

import dev.smithyai.orchestrator.config.AgentConfig.ClaudeAgentConfig;
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

    /**
     * How long a human message waits for an in-flight turn before giving up. Kept
     * short on purpose: the answer "the agent is busy" is useful immediately and
     * useless after a queue.
     */
    private static final Duration BUSY_WAIT = Duration.ofSeconds(5);

    /**
     * Budget for a turn a person is waiting on in a browser. Much shorter than a
     * build turn's — an unanswered request is a hung dashboard, not a long job.
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    /** Used only when there is no config bean at all — see agent.claude.takeoverTools. */
    private static final List<String> DEFAULT_TOOLS = List.of(
        "Read",
        "Glob",
        "Grep",
        "Bash",
        "Edit",
        "Write",
        "WebFetch"
    );

    private final RunStore store;
    private final RunEnvironments environments;
    private final RunLocks locks;
    private final Duration turnTimeout;
    private final List<String> defaultTools;

    public RunTakeover(RunStore store, RunEnvironments environments, RunLocks locks, ClaudeAgentConfig claude) {
        this.store = store;
        this.environments = environments;
        this.locks = locks;
        this.turnTimeout = claude == null ? DEFAULT_TIMEOUT : claude.resolvedTakeoverTimeout().orElse(DEFAULT_TIMEOUT);
        this.defaultTools = claude == null ? DEFAULT_TOOLS : claude.resolvedTakeoverTools();
    }

    /**
     * The tools a human-driven turn may use.
     *
     * <p>Never an empty list. {@code ClaudeSession} omits {@code --allowedTools}
     * when it has nothing to put there, and a headless turn on default
     * permissions then refuses every tool call before it runs — the agent cannot
     * read a file, let alone push a branch. That looks like an agent that has
     * stopped cooperating rather than one that was handed no permissions, so the
     * empty case is filled in here instead of being passed on.
     */
    private List<String> toolsFor(List<String> requested) {
        return requested == null || requested.isEmpty() ? defaultTools : requested;
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
     *
     * <p>Takes the run's turn lock, because holding the lease is not the same as
     * the session being free. The lease stops <em>new</em> events being
     * dispatched; a turn that was already running when someone took over keeps
     * running, and firing a second {@code claude --resume} at the same session
     * gets a message that never lands and a request that hangs until the budget
     * runs out. So this reports the collision instead of joining it.
     *
     * @throws AgentBusyException if a turn is already in flight for this run
     */
    public String send(Run run, String text, List<String> tools) {
        heartbeat(run.id());
        return locks
            .tryInRun(run.id(), BUSY_WAIT, () -> {
                var container = environments.container(run);
                var agent = environments.agent(run, toolsFor(tools));
                agent.setTurnTimeout(turnTimeout);
                String reply = agent.send(text);
                environments.rememberAgentSession(container, agent);
                store.appendEvent(run.id(), "takeover.message", Map.of("length", text.length()));
                return reply == null ? "" : reply;
            })
            .orElseThrow(() -> {
                store.appendEvent(run.id(), "takeover.message.busy", Map.of("length", text.length()));
                log.info("Held a takeover message on run {}: a turn is already in flight", run.id());
                return new AgentBusyException(run.id());
            });
    }
}
