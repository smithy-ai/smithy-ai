package dev.smithyai.orchestrator.runtime.definition;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record WorkflowStepDefinition(
    String uses,
    String id,
    @JsonProperty("if") String condition,
    Map<String, Object> with,
    List<WorkflowStepDefinition> steps
) {
    public Map<String, Object> with() {
        return with != null ? with : Map.of();
    }

    /**
     * Nested steps, for actions that iterate — the flat step list had no way to
     * express a fan-out over a collection.
     */
    public List<WorkflowStepDefinition> steps() {
        return steps != null ? steps : List.of();
    }
}
