package dev.smithyai.orchestrator.runtime.store;

import java.time.Instant;

/**
 * Something a run is blocked on.
 *
 * <p>The kind records who is expected to release it — a human, a sibling run —
 * but the key is what identifies it, and any of them may satisfy it.
 */
public record RunWait(long id, String runId, String kind, String waitKey, Instant satisfiedAt, Instant createdAt) {
    public boolean satisfied() {
        return satisfiedAt != null;
    }
}
