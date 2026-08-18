package dev.smithyai.orchestrator.runtime.definition;

import java.time.Duration;
import java.util.List;

/**
 * What one state does with one event.
 *
 * @param debounce how long to wait for more of the same event before running,
 *                 e.g. {@code 30s}. Review comments arrive in bursts — a
 *                 reviewer submitting several notes — and handling each on its
 *                 own gives an agent turn and a commit per comment. Waiting
 *                 makes the burst one turn and one commit, and the steps see
 *                 the whole batch.
 */
public record WorkflowTransitionDefinition(String to, String debounce, List<WorkflowStepDefinition> steps) {
    public List<WorkflowStepDefinition> steps() {
        return steps != null ? steps : List.of();
    }

    /** The debounce window, or null when the transition runs immediately. */
    public Duration debounceWindow() {
        if (debounce == null || debounce.isBlank()) return null;
        String value = debounce.strip().toLowerCase();
        try {
            if (value.endsWith("ms")) return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
            if (value.endsWith("s")) return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
            if (value.endsWith("m")) return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
            return Duration.ofSeconds(Long.parseLong(value));
        } catch (NumberFormatException e) {
            throw new WorkflowDefinitionException("Cannot read a duration from debounce '" + debounce + "'", e);
        }
    }
}
