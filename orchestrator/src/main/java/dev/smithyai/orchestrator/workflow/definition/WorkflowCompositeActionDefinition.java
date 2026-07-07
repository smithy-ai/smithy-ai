package dev.smithyai.orchestrator.workflow.definition;

import java.util.List;
import java.util.Map;

public record WorkflowCompositeActionDefinition(Map<String, Object> inputs, List<WorkflowStepDefinition> steps) {
    public Map<String, Object> inputs() {
        return inputs != null ? inputs : Map.of();
    }

    public List<WorkflowStepDefinition> steps() {
        return steps != null ? steps : List.of();
    }
}
