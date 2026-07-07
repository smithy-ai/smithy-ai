package dev.smithyai.orchestrator.workflow.definition;

import java.util.Map;

public record WorkflowStageDefinition(Map<String, WorkflowTransitionDefinition> on) {
    public Map<String, WorkflowTransitionDefinition> on() {
        return on != null ? on : Map.of();
    }
}
