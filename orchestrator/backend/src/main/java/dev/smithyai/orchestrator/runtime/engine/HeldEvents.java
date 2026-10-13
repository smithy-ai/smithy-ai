package dev.smithyai.orchestrator.runtime.engine;

import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Events that arrived while a person held a run.
 *
 * <p>Holding used to mean dropping: the event was logged and gone, so a label
 * added while the session tab was still open was an approval nobody acted on.
 * Held now means deferred — kept here, oldest first, and handed back once
 * control is released.
 *
 * <p>In memory on purpose. A takeover is an interactive thing measured in
 * minutes, and an orchestrator restart in the middle of one is rare enough
 * that re-sending the event beats a persistence layer for it. Bounded, and
 * deduplicated on the event's own identity, so a provider that redelivers a
 * webhook while someone is typing does not queue the same approval twice.
 */
final class HeldEvents {

    /** More than this held for one run means something is wrong, not that the person is thorough. */
    static final int CAP = 50;

    private final Map<String, List<WorkflowEvent>> byRun = new ConcurrentHashMap<>();

    /** Keep an event for later. Returns how many are now held for the run. */
    int hold(String runId, WorkflowEvent event) {
        var list = byRun.computeIfAbsent(runId, id -> new ArrayList<>());
        synchronized (list) {
            String key = key(event);
            boolean duplicate = list.stream().anyMatch(held -> key(held).equals(key));
            if (!duplicate && list.size() < CAP) list.add(event);
            return list.size();
        }
    }

    /** Everything held for the run, in arrival order; the run holds nothing afterwards. */
    List<WorkflowEvent> drain(String runId) {
        var list = byRun.remove(runId);
        if (list == null) return List.of();
        synchronized (list) {
            return List.copyOf(list);
        }
    }

    int count(String runId) {
        var list = byRun.get(runId);
        if (list == null) return 0;
        synchronized (list) {
            return list.size();
        }
    }

    private static String key(WorkflowEvent event) {
        return event.name() + "|" + event.identity();
    }
}
