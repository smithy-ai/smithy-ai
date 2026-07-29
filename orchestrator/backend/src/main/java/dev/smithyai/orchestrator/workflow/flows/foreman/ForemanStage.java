package dev.smithyai.orchestrator.workflow.flows.foreman;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ForemanStage {
    NEW("new"),
    AWAITING_APPROVAL("awaiting_approval"),
    EXECUTING("executing"),
    DONE("done");

    private final String value;

    ForemanStage(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ForemanStage fromValue(String value) {
        for (ForemanStage s : values()) {
            if (s.value.equals(value)) return s;
        }
        throw new IllegalArgumentException("Unknown foreman stage: " + value);
    }
}
