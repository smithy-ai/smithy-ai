package dev.smithyai.orchestrator.workflow.flows.architect;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum LearnStage {
    NEW("new"),
    LEARNING("learning"),
    DONE("done");

    private final String value;

    LearnStage(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static LearnStage fromValue(String value) {
        for (LearnStage s : values()) {
            if (s.value.equals(value)) return s;
        }
        throw new IllegalArgumentException("Unknown stage: " + value);
    }
}
