package dev.smithyai.orchestrator.runtime.engine;

import dev.smithyai.orchestrator.config.AgentConfig.ClaudeAgentConfig;
import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.runtime.store.Run;
import dev.smithyai.orchestrator.runtime.store.RunLease;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

    /** Where an uploaded screenshot lands, alongside the run's other scratch material. */
    private static final String SCREENSHOTS_DIR = "/workspace/.smithy/tmp/takeover";

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(
        ZoneOffset.UTC
    );

    /**
     * An image somebody pasted, dropped or picked in the takeover composer.
     *
     * <p>A screenshot is often the whole message — a broken layout, a stack
     * trace, a design someone is pointing at — and describing one in words is
     * strictly worse than showing it.
     */
    public record Screenshot(String filename, byte[] data) {}

    private final RunStore store;
    private final RunEnvironments environments;
    private final RunLocks locks;
    private final Duration turnTimeout;

    public RunTakeover(RunStore store, RunEnvironments environments, RunLocks locks, ClaudeAgentConfig claude) {
        this.store = store;
        this.environments = environments;
        this.locks = locks;
        this.turnTimeout = claude == null ? DEFAULT_TIMEOUT : claude.resolvedTakeoverTimeout().orElse(DEFAULT_TIMEOUT);
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
        return send(run, text, List.of(), tools);
    }

    /**
     * The same, with images attached.
     *
     * <p>The files are written into the container and the prompt names them by
     * path, because the agent reads an image the way it reads any other file and
     * cannot be handed bytes down the wire.
     *
     * @throws AgentBusyException if a turn is already in flight for this run
     */
    public String send(Run run, String text, List<Screenshot> screenshots, List<String> tools) {
        heartbeat(run.id());
        return locks
            .tryInRun(run.id(), BUSY_WAIT, () -> {
                var container = environments.container(run);
                // Inside the lock: the container a turn runs in is the one the
                // screenshots have to land in, and nothing may swap it between.
                var paths = inject(container, screenshots);
                var agent = environments.agent(run, tools);
                agent.setTurnTimeout(turnTimeout);
                String reply = agent.send(compose(text, paths));
                environments.rememberAgentSession(container, agent);
                store.appendEvent(
                    run.id(),
                    "takeover.message",
                    Map.of("length", text.length(), "screenshots", paths.size())
                );
                return reply == null ? "" : reply;
            })
            .orElseThrow(() -> {
                store.appendEvent(run.id(), "takeover.message.busy", Map.of("length", text.length()));
                log.info("Held a takeover message on run {}: a turn is already in flight", run.id());
                return new AgentBusyException(run.id());
            });
    }

    /**
     * Copy the screenshots into the container.
     *
     * <p>Best-effort per file: one image that will not write is one the agent
     * answers without, which beats losing the message it came with.
     */
    private List<String> inject(ContainerSession container, List<Screenshot> screenshots) {
        if (screenshots.isEmpty()) return List.of();
        if (!container.ensureScratchDir(SCREENSHOTS_DIR)) return List.of();

        // A timestamp keeps successive pastes apart: they arrive named
        // "image.png" or nothing at all, and the second must not overwrite the
        // first while the agent is still looking at it.
        String stamp = TIMESTAMP.format(Instant.now());
        var paths = new ArrayList<String>();
        for (int i = 0; i < screenshots.size(); i++) {
            var screenshot = screenshots.get(i);
            String filename = "%s-%d-%s".formatted(stamp, i + 1, safeName(screenshot.filename()));
            try {
                container.copyToContainer(SCREENSHOTS_DIR, screenshot.data(), filename);
                paths.add(SCREENSHOTS_DIR + "/" + filename);
            } catch (Exception e) {
                log.warn("Failed to copy screenshot {} into {}", filename, container.getContainerName(), e);
            }
        }
        return List.copyOf(paths);
    }

    /**
     * The message the agent actually receives.
     *
     * <p>The paths are spelled out and the agent is told to open them, because a
     * file it does not know about is a file it does not read — and a person who
     * attached a screenshot has already said everything they mean to say about
     * it.
     */
    private static String compose(String text, List<String> paths) {
        if (paths.isEmpty()) return text;
        var message = new StringBuilder(text.isBlank() ? "Look at the attached screenshot(s)." : text);
        message.append("\n\n## Screenshots\n\n");
        message.append("Attached to this message and saved in this container. Read them before you reply:\n");
        paths.forEach(path -> message.append("- `").append(path).append("`\n"));
        return message.toString();
    }

    /**
     * A filename safe to interpolate into a shell command and readable in a
     * transcript. Pasted images often arrive nameless, so there is a fallback.
     */
    private static String safeName(String filename) {
        String name = filename == null ? "" : filename.strip();
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[-.]+", "");
        if (name.length() > 60) name = name.substring(name.length() - 60);
        return name.isBlank() ? "screenshot.png" : name;
    }
}
