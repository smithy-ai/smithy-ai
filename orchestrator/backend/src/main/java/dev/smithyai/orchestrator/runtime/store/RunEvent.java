package dev.smithyai.orchestrator.runtime.store;

import java.time.Instant;
import java.util.Map;

/** One entry in a run's append-only history. */
public record RunEvent(long id, String runId, long seq, Instant ts, String type, Map<String, Object> payload) {}
