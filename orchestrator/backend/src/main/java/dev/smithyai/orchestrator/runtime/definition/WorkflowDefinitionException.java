package dev.smithyai.orchestrator.runtime.definition;

import java.util.List;

public class WorkflowDefinitionException extends RuntimeException {

    private final List<String> errors;

    public WorkflowDefinitionException(String message, List<String> errors) {
        super(message + ": " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    /** A single problem with a definition, where a list would just be noise. */
    public WorkflowDefinitionException(String message) {
        super(message);
        this.errors = List.of(message);
    }

    public WorkflowDefinitionException(String message, Throwable cause) {
        super(message, cause);
        this.errors = List.of(message);
    }

    public List<String> errors() {
        return errors;
    }
}
