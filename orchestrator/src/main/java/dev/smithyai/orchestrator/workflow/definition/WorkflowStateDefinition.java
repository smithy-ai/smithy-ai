package dev.smithyai.orchestrator.workflow.definition;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.LinkedHashMap;
import java.util.Map;

public class WorkflowStateDefinition {

    private String initial;
    private String terminal;
    private final Map<String, WorkflowStageDefinition> stages = new LinkedHashMap<>();

    public String getInitial() {
        return initial;
    }

    public void setInitial(String initial) {
        this.initial = initial;
    }

    public String getTerminal() {
        return terminal;
    }

    public void setTerminal(String terminal) {
        this.terminal = terminal;
    }

    public Map<String, WorkflowStageDefinition> getStages() {
        return Map.copyOf(stages);
    }

    @JsonAnySetter
    public void addStage(String name, WorkflowStageDefinition stage) {
        stages.put(name, stage);
    }
}
