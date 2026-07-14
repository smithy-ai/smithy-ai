package dev.smithyai.orchestrator.workflow.flows.smithy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Stage {
    NEW("new"),
    REFINE("refine"),
    BUILD("build"),
    DONE("done");

    private final String value;

    Stage(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static Stage fromValue(String value) {
        for (Stage s : values()) {
            if (s.value.equals(value)) return s;
        }
        throw new IllegalArgumentException("Unknown stage: " + value);
    }
}
