package dev.smithyai.orchestrator.runtime.engine;

/**
 * A turn was asked for while the run's agent session was already in one.
 *
 * <p>Raised instead of waiting: the session cannot take a second concurrent
 * process, and a person typing in the dashboard needs to hear that now rather
 * than watch a request hang until the turn budget runs out.
 */
public class AgentBusyException extends RuntimeException {

    public AgentBusyException(String runId) {
        super(
            (
                "The agent on run %s is in the middle of a turn. Its session cannot take a second message " +
                "until that finishes — wait for the current turn, or stop the run."
            ).formatted(runId)
        );
    }
}
