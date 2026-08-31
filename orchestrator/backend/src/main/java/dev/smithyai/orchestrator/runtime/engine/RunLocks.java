package dev.smithyai.orchestrator.runtime.engine;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * One agent turn at a time per run.
 *
 * <p>A run's turns all talk to one Claude session in one container, and the CLI
 * is not built to have two processes resuming the same session at once. Two
 * webhooks arriving together are one way that happens; a human taking over
 * mid-turn and typing into the same session is the other. Both go through here,
 * which is the point — a guard the takeover path did not share was a guard the
 * takeover path did not have.
 *
 * <p>In-process is enough while one orchestrator owns the store, and unlike a
 * row in a table it leaves nothing behind if the process dies.
 */
@Component
public class RunLocks {

    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** Run {@code work} with the run to itself, waiting however long that takes. */
    public <T> T inRun(String runId, Supplier<T> work) {
        var lock = lockFor(runId);
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * The same, but give up after {@code wait} instead of queueing.
     *
     * <p>Empty means someone else is mid-turn. A caller with a person waiting on
     * the other end wants to say so now, not in forty minutes.
     */
    public <T> Optional<T> tryInRun(String runId, Duration wait, Supplier<T> work) {
        var lock = lockFor(runId);
        try {
            if (!lock.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS)) return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(work.get());
        } finally {
            lock.unlock();
        }
    }

    /** Whether a turn is in flight for this run. */
    public boolean isBusy(String runId) {
        var lock = locks.get(runId);
        return lock != null && lock.isLocked();
    }

    private ReentrantLock lockFor(String runId) {
        // Reentrant because the monitor this replaced was: a step that dispatches
        // an event for its own run must not deadlock against itself.
        return locks.computeIfAbsent(runId, id -> new ReentrantLock());
    }
}
