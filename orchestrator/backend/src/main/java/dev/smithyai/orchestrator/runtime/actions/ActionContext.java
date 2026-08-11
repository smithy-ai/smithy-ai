package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.store.Run;
import java.util.Map;

/**
 * What an action is given: the run it acts for, the event that triggered the
 * transition, the outputs of earlier steps, and the run's variables.
 *
 * @param steps outputs of previously completed steps in this transition, keyed
 *              by step id — the backing for {@code steps.<id>.<field>}
 */
public record ActionContext(
    Run run,
    WorkflowEvent event,
    Map<String, Map<String, Object>> steps,
    Map<String, Object> vars
) {
    public Object stepOutput(String stepId, String key) {
        var outputs = steps.get(stepId);
        return outputs == null ? null : outputs.get(key);
    }
}
