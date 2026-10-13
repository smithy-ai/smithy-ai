package dev.smithyai.orchestrator.runtime.engine;

import dev.smithyai.orchestrator.config.AgentConfig.ClaudeAgentConfig;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.runtime.store.Run;
import dev.smithyai.orchestrator.runtime.store.RunLease;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A human driving a run's agent session directly.
 *
 * <p>Sometimes the fastest way through is to talk to the agent yourself. While
 * someone holds a run, inbound events are not handled — an agent acting on a
 * webhook on top of what a person is typing into the same session produces
 * work neither of them asked for. They are held, and handed back once the
 * person lets go.
 *
 * <p>Control is a heartbeat, not a flag. Someone who takes over and then closes
 * the tab must not leave the run frozen, so it lapses on its own unless the
 * dashboard keeps renewing it, and a sweep notices the lapse so what was held
 * still gets handled.
 *
 * <p>Letting go comes in two forms. Releasing says "carry on as you were".
 * Finishing says "I'm done, act on what we discussed": the workflow gets a
 * {@code takeover-finished} signal before anything held is replayed, so a
 * planning workflow can write the agreed plan down before a held approval
 * executes it.
 */
@Slf4j
@Component
public class RunTakeover {

    /** What a workflow listens for to act on a session a person finished with. */
    public static final String FINISHED = "takeover-finished";

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
    private final RunEngine engine;
    private final Duration turnTimeout;
    private final List<String> defaultTools;

    /** Runs a person currently holds, with how many messages they have sent. */
    private final Map<String, AtomicInteger> active = new ConcurrentHashMap<>();

    /**
     * Where the work of letting go runs. A hand-back triggers an agent turn
     * that takes minutes, and the request that triggered it is a button click
     * that wants a prompt answer; the engine's per-run lock keeps the turn and
     * the replay behind it in order.
     */
    private final ExecutorService handback = Executors.newThreadPerTaskExecutor(
        Thread.ofPlatform().name("takeover-handback-", 0).factory()
    );

    public RunTakeover(
        RunStore store,
        RunEnvironments environments,
        RunLocks locks,
        ClaudeAgentConfig claude,
        @Lazy RunEngine engine
    ) {
        this.store = store;
        this.environments = environments;
        this.locks = locks;
        this.engine = engine;
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
            active.put(runId, new AtomicInteger());
            store.appendEvent(runId, "takeover.started", null);
            log.info("A human took over run {}", runId);
        }
        return expiry;
    }

    /** Let go without asking anything of the workflow; what was held is handled now. */
    public void release(String runId) {
        release(runId, "dashboard");
    }

    private void release(String runId, String via) {
        var ended = end(runId);
        if (ended.isEmpty()) return;
        store.appendEvent(
            runId,
            "takeover.released",
            Map.of("via", via, "messages", ended.get().messages(), "held", ended.get().held().size())
        );
        log.info(
            "Human takeover released on run {} ({}; {} message(s), {} held event(s))",
            runId,
            via,
            ended.get().messages(),
            ended.get().held().size()
        );
        if (!ended.get().held().isEmpty()) {
            var held = ended.get().held();
            handback.execute(() -> replay(runId, held));
        }
    }

    /**
     * Let go and tell the workflow to act on the conversation.
     *
     * <p>The signal goes first and the held events after it, on one thread, so
     * a workflow that writes down what was agreed sees that happen before a
     * held approval runs it. The engine's run lock means an in-flight turn is
     * waited for rather than collided with. The lease is already gone when
     * the signal arrives: otherwise the engine would hold it too.
     */
    public void finish(Run run) {
        var ended = end(run.id());
        int messages = ended.map(Ended::messages).orElse(0);
        var held = ended.map(Ended::held).orElse(List.of());
        store.appendEvent(run.id(), "takeover.finished", Map.of("messages", messages, "held", held.size()));
        log.info(
            "Human handed run {} back to its workflow ({} message(s), {} held event(s))",
            run.id(),
            messages,
            held.size()
        );

        var payload = new LinkedHashMap<String, Object>();
        payload.put("messages", messages);
        // Each hand-back is its own occurrence, however many happen in one state.
        payload.put("id", UUID.randomUUID().toString());
        payload.put("via", "dashboard");
        var signal = new WorkflowEvent.Signal(RunEngine.repoOf(run), FINISHED, payload);

        handback.execute(() -> {
            try {
                boolean handled = engine.deliver(run.id(), signal);
                log.info("Run {} {} the {} signal", run.id(), handled ? "handled" : "did not handle", FINISHED);
            } catch (RuntimeException e) {
                // The held events still deserve their turn.
                log.error("Run {} failed on the {} signal", run.id(), FINISHED, e);
            }
            replay(run.id(), held);
        });
    }

    /** A takeover that has ended: what the person sent, and what arrived meanwhile. */
    private record Ended(int messages, List<WorkflowEvent> held) {}

    /** Drop the lease and collect what was held. Empty when nobody held the run. */
    private Optional<Ended> end(String runId) {
        boolean wasHeld = store.isLeased(runId) || active.containsKey(runId);
        store.releaseLease(runId);
        var counter = active.remove(runId);
        var held = engine.drainHeld(runId);
        if (!wasHeld && held.isEmpty()) return Optional.empty();
        return Optional.of(new Ended(counter == null ? 0 : counter.get(), held));
    }

    private void replay(String runId, List<WorkflowEvent> held) {
        if (held.isEmpty()) return;
        int handled = engine.redeliverAll(runId, held);
        log.info("Run {}: replayed {} held event(s), {} handled", runId, held.size(), handled);
    }

    /**
     * A closed tab whose goodbye never arrived. The lease has lapsed on its own;
     * this notices, so what was held while it was alive is not held forever.
     */
    @Scheduled(fixedDelay = 10_000)
    public void sweepLapsed() {
        for (String runId : List.copyOf(active.keySet())) {
            if (!store.isLeased(runId)) release(runId, "lapse");
        }
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
                var sent = active.get(run.id());
                if (sent != null) sent.incrementAndGet();
                return reply == null ? "" : reply;
            })
            .orElseThrow(() -> {
                store.appendEvent(run.id(), "takeover.message.busy", Map.of("length", text.length()));
                log.info("Held a takeover message on run {}: a turn is already in flight", run.id());
                return new AgentBusyException(run.id());
            });
    }
}
