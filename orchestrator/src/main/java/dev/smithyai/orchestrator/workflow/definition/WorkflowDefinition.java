package dev.smithyai.orchestrator.workflow.definition;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record WorkflowDefinition(
    String apiVersion,
    String kind,
    WorkflowMetadata metadata,
    Map<String, Object> defaults,
    List<WorkflowRoutingRule> routing,
    WorkflowStateDefinition state,
    Map<String, WorkflowCompositeActionDefinition> actions
) {
    public Map<String, Object> defaults() {
        return defaults != null ? defaults : Map.of();
    }

    public List<WorkflowRoutingRule> routing() {
        return routing != null ? routing : List.of();
    }

    public Map<String, WorkflowCompositeActionDefinition> actions() {
        return actions != null ? actions : Map.of();
    }

    public record WorkflowMetadata(String name, @JsonProperty("extends") String extendsWorkflow) {}
}
