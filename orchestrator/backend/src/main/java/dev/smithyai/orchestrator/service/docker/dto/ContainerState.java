package dev.smithyai.orchestrator.service.docker.dto;

import java.time.Instant;

public record ContainerState(
    String sessionId,
    String stage,
    WorkflowType workflowType,
    int ciRetryCount,
    boolean ciPaused,
    Instant lastProcessedAt
) {
    public static ContainerState init(WorkflowType workflowType, String stage) {
        return new ContainerState(null, stage, workflowType, 0, false, Instant.now());
    }

    public ContainerState withSessionId(String sessionId) {
        return new ContainerState(sessionId, stage, workflowType, ciRetryCount, ciPaused, lastProcessedAt);
    }

    public ContainerState withStage(String stage) {
        return new ContainerState(sessionId, stage, workflowType, ciRetryCount, ciPaused, lastProcessedAt);
    }

    public ContainerState withCiRetryCount(int count) {
        return new ContainerState(sessionId, stage, workflowType, count, ciPaused, lastProcessedAt);
    }

    public ContainerState withCiPaused(boolean paused) {
        return new ContainerState(sessionId, stage, workflowType, ciRetryCount, paused, lastProcessedAt);
    }

    public ContainerState incrementCiRetryCount() {
        return new ContainerState(sessionId, stage, workflowType, ciRetryCount + 1, ciPaused, lastProcessedAt);
    }

    public ContainerState resetCi() {
        return new ContainerState(sessionId, stage, workflowType, 0, false, lastProcessedAt);
    }

    public ContainerState touch() {
        return new ContainerState(sessionId, stage, workflowType, ciRetryCount, ciPaused, Instant.now());
    }
}
