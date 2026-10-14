package dev.smithyai.orchestrator.runtime.engine;

import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>Every mutation happens inside the map's own compute, so a hold racing a
 * drain lands in the list the next drain sees rather than in one nobody does.
 */
final class HeldEvents {

    /** More than this held for one run means something is wrong, not that the person is thorough. */
    static final int CAP = 50;

    /** What became of an event offered for holding. */
    enum Outcome {
        HELD,
        DUPLICATE,
        CAPPED,
    }

    record Held(Outcome outcome, int count) {}

    private final Map<String, List<WorkflowEvent>> byRun = new ConcurrentHashMap<>();

    /** Keep an event for later, and say whether it was. */
    Held hold(String runId, WorkflowEvent event) {
        var result = new Held[1];
        byRun.compute(runId, (id, existing) -> {
            var list = existing == null ? new ArrayList<WorkflowEvent>() : existing;
            String key = key(event);
            if (list.stream().anyMatch(held -> key(held).equals(key))) {
                result[0] = new Held(Outcome.DUPLICATE, list.size());
            } else if (list.size() >= CAP) {
                result[0] = new Held(Outcome.CAPPED, list.size());
            } else {
                list.add(event);
                result[0] = new Held(Outcome.HELD, list.size());
            }
            return list;
        });
        return result[0];
    }

    /** Everything held for the run, in arrival order; the run holds nothing afterwards. */
    List<WorkflowEvent> drain(String runId) {
        var list = byRun.remove(runId);
        return list == null ? List.of() : List.copyOf(list);
    }

    boolean hasAny(String runId) {
        var list = byRun.get(runId);
        return list != null && !list.isEmpty();
    }

    /** The runs holding anything right now. */
    Set<String> runIds() {
        return byRun
            .entrySet()
            .stream()
            .filter(e -> !e.getValue().isEmpty())
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet());
    }

    private static String key(WorkflowEvent event) {
        return event.name() + "|" + event.identity();
    }
}
