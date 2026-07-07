package dev.smithyai.orchestrator.workflow.definition;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record WorkflowStepDefinition(
    String uses,
    String id,
    @JsonProperty("if") String condition,
    Map<String, Object> with
) {
    public Map<String, Object> with() {
        return with != null ? with : Map.of();
    }
}
