package dev.smithyai.orchestrator.workflow.definition;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.List;

public record WorkflowRoutingRule(
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> event,
    WorkflowRoutingAction action,
    String key
) {
    public List<String> event() {
        return event != null ? event : List.of();
    }
}
