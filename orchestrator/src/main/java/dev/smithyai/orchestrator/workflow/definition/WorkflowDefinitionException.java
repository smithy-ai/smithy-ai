package dev.smithyai.orchestrator.workflow.definition;

import java.util.List;

public class WorkflowDefinitionException extends RuntimeException {

    private final List<String> errors;

    public WorkflowDefinitionException(String message, List<String> errors) {
        super(message + ": " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
