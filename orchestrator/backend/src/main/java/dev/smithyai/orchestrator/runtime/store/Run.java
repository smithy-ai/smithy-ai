package dev.smithyai.orchestrator.runtime.store;

import java.time.Instant;
import java.util.Map;

/**
 * One execution of a workflow. The run — not the container it may hold — is the
 * durable identity: it survives the container being removed, and it is what the
 * dashboard lists and what correlations point at.
 *
 * @param workflowName string name, deliberately not an enum, so adding a
 *                     workflow does not mean editing a core type
 * @param state        the current state-machine state
 * @param vars         workflow-defined variables
 */
public record Run(
    String id,
    String workflowName,
    String workflowVersion,
    RunStatus status,
    String state,
    Map<String, Object> vars,
    String parentRunId,
    String rootRunId,
    Instant createdAt,
    Instant updatedAt,
    Instant terminalAt
) {
    public boolean isTerminal() {
        return status.isTerminal();
    }

    /** A run with no parent is its own root. */
    public String resolvedRootRunId() {
        return rootRunId != null ? rootRunId : id;
    }
}
