package dev.smithyai.orchestrator.runtime.engine;

import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Collects a burst of the same event into one delivery.
 *
 * <p>Review comments arrive several at a time — a reviewer submitting notes on
 * four files — and handling each on its own gives an agent turn and a commit per
 * comment, which reads terribly on the pull request and costs four times as
 * much. The first event of a burst opens a window; everything that lands inside
 * it joins the same batch, and the transition runs once when the window closes.
 *
 * <p>A platform concern rather than a workflow one, which is why it moved here
 * out of the flow that had it: a definition asks for it with one line, and does
 * not manage a queue.
 */
@Slf4j
@Component
public class EventDebouncer {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
        1,
        Thread.ofVirtual().name("debounce-", 0).factory()
    );

    private final ConcurrentMap<String, Batch> batches = new ConcurrentHashMap<>();

    /**
     * Add an event to its batch, opening a window if this is the first.
     *
     * @param key   what the batch is per — a run and an event name
     * @param flush runs once when the window closes, with everything collected
     */
    public void submit(String key, Duration window, WorkflowEvent event, Consumer<List<WorkflowEvent>> flush) {
        var batch = batches.computeIfAbsent(key, ignored -> new Batch());
        boolean first;
        synchronized (batch) {
            first = batch.events.isEmpty();
            batch.events.add(event);
        }
        if (!first) {
            log.debug("Event {} joined the open batch for {}", event.name(), key);
            return;
        }

        scheduler.schedule(
            () -> {
                var collected = batches.remove(key);
                if (collected == null) return;
                List<WorkflowEvent> events;
                synchronized (collected) {
                    events = List.copyOf(collected.events);
                }
                log.info("Delivering {} batched {} event(s) for {}", events.size(), event.name(), key);
                try {
                    flush.accept(events);
                } catch (RuntimeException e) {
                    // Nothing is waiting on this thread, so a failure here would
                    // otherwise vanish into the scheduler.
                    log.error("Batched delivery failed for {}", key, e);
                }
            },
            window.toMillis(),
            java.util.concurrent.TimeUnit.MILLISECONDS
        );
    }

    /** Whether anything is currently waiting to be delivered. */
    public boolean isPending(String key) {
        return batches.containsKey(key);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private static final class Batch {

        private final List<WorkflowEvent> events = new ArrayList<>();
    }
}
