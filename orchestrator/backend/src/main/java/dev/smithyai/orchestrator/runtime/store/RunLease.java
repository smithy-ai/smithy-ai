package dev.smithyai.orchestrator.runtime.store;

import java.time.Instant;

/**
 * Who currently controls a run.
 *
 * <p>Carries the human-takeover lease: while a person holds one, inbound events
 * are not handled, so the agent does not act on top of whatever the human is
 * doing in the same session.
 */
public record RunLease(String runId, String holder, Instant expiresAt) {
    /** The lease a human takes when they take over a session. */
    public static final String HUMAN = "human";

    public boolean expired() {
        return expiresAt == null || Instant.now().isAfter(expiresAt);
    }
}
