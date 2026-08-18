package dev.smithyai.orchestrator.runtime.store;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum RunStatus {
    /** Created, not yet started. */
    PENDING("pending"),
    /** Actively handling an event. */
    RUNNING("running"),
    /** Idle between events, or blocked on a gate or a join. */
    WAITING("waiting"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String value;

    RunStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    @JsonCreator
    public static RunStatus fromValue(String value) {
        for (RunStatus s : values()) {
            if (s.value.equals(value)) return s;
        }
        throw new IllegalArgumentException("Unknown run status: " + value);
    }
}
