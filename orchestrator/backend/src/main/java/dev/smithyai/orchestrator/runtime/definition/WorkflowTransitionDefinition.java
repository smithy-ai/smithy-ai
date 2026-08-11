package dev.smithyai.orchestrator.runtime.definition;

import java.util.List;

public record WorkflowTransitionDefinition(String to, List<WorkflowStepDefinition> steps) {
    public List<WorkflowStepDefinition> steps() {
        return steps != null ? steps : List.of();
    }
}
