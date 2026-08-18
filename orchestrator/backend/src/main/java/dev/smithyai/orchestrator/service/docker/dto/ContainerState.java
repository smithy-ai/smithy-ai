package dev.smithyai.orchestrator.service.docker.dto;

import java.time.Instant;

public record ContainerState(
    String sessionId,
    String stage,
    String workflow,
    int ciRetryCount,
    boolean ciPaused,
    Instant lastProcessedAt
) {
    public static ContainerState init(String workflow, String stage) {
        return new ContainerState(null, stage, workflow, 0, false, Instant.now());
    }

    public ContainerState withSessionId(String sessionId) {
        return new ContainerState(sessionId, stage, workflow, ciRetryCount, ciPaused, lastProcessedAt);
    }

    public ContainerState withStage(String stage) {
        return new ContainerState(sessionId, stage, workflow, ciRetryCount, ciPaused, lastProcessedAt);
    }

    public ContainerState withCiRetryCount(int count) {
        return new ContainerState(sessionId, stage, workflow, count, ciPaused, lastProcessedAt);
    }

    public ContainerState withCiPaused(boolean paused) {
        return new ContainerState(sessionId, stage, workflow, ciRetryCount, paused, lastProcessedAt);
    }

    public ContainerState incrementCiRetryCount() {
        return new ContainerState(sessionId, stage, workflow, ciRetryCount + 1, ciPaused, lastProcessedAt);
    }

    public ContainerState resetCi() {
        return new ContainerState(sessionId, stage, workflow, 0, false, lastProcessedAt);
    }

    public ContainerState touch() {
        return new ContainerState(sessionId, stage, workflow, ciRetryCount, ciPaused, Instant.now());
    }
}
