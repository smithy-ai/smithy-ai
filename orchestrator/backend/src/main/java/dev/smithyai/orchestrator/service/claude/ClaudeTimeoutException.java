package dev.smithyai.orchestrator.service.claude;

import java.time.Duration;
import lombok.Getter;

/**
 * An agent turn that ran past its budget and was killed.
 *
 * <p>Separate from a generic process failure because the remedy is different:
 * neither the prompt nor the container is broken, the work simply did not fit in
 * the time allowed. Extends {@link IllegalStateException} so anything written
 * against the failure this replaced still catches it.
 */
@Getter
public class ClaudeTimeoutException extends IllegalStateException {

    private final Duration budget;
    private final String containerName;
    private final String sessionId;

    /** Whatever the CLI had written before it was killed — usually empty. */
    private final String partialOutput;

    public ClaudeTimeoutException(Duration budget, String containerName, String sessionId, String partialOutput) {
        super(
            (
                "Claude turn on %s (session=%s) exceeded its %s budget and was killed. " +
                "Raise agent.claude.turnTimeout in orchestrator.yml, or split the step into smaller turns."
            ).formatted(containerName, sessionId, human(budget))
        );
        this.budget = budget;
        this.containerName = containerName;
        this.sessionId = sessionId;
        this.partialOutput = partialOutput == null ? "" : partialOutput;
    }

    private static String human(Duration budget) {
        long minutes = budget.toMinutes();
        return minutes > 0 ? minutes + "m" : budget.toSeconds() + "s";
    }
}
