package dev.smithyai.orchestrator.workflow.flows.architect;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ReviewStage {
    NEW("new"),
    REVIEWING("reviewing"),
    DONE("done");

    private final String value;

    ReviewStage(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ReviewStage fromValue(String value) {
        for (ReviewStage s : values()) {
            if (s.value.equals(value)) return s;
        }
        throw new IllegalArgumentException("Unknown stage: " + value);
    }
}
