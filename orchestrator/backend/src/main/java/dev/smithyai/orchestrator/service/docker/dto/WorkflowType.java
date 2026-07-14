package dev.smithyai.orchestrator.service.docker.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum WorkflowType {
    SMITHY("smithy"),
    ARCHITECT("architect");

    private final String value;

    WorkflowType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static WorkflowType fromValue(String value) {
        for (WorkflowType t : values()) {
            if (t.value.equals(value)) return t;
        }
        throw new IllegalArgumentException("Unknown workflow type: " + value);
    }
}
