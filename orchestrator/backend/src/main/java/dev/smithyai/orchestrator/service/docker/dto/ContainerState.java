package dev.smithyai.orchestrator.service.docker.dto;

import java.time.Instant;
import java.util.List;

public record ContainerState(
    String sessionId,
    String stage,
    String workflow,
    int ciRetryCount,
    boolean ciPaused,
    Instant lastProcessedAt,
    List<String> extraDirs
) {
    public ContainerState {
        // State written before this field existed has no extraDirs.
        if (extraDirs == null) extraDirs = List.of();
    }

    public static ContainerState init(String workflow, String stage) {
        return new ContainerState(null, stage, workflow, 0, false, Instant.now(), List.of());
    }

    public ContainerState withSessionId(String sessionId) {
        return new ContainerState(sessionId, stage, workflow, ciRetryCount, ciPaused, lastProcessedAt, extraDirs);
    }

    public ContainerState withStage(String stage) {
        return new ContainerState(sessionId, stage, workflow, ciRetryCount, ciPaused, lastProcessedAt, extraDirs);
    }

    public ContainerState withCiRetryCount(int count) {
        return new ContainerState(sessionId, stage, workflow, count, ciPaused, lastProcessedAt, extraDirs);
    }

    public ContainerState withCiPaused(boolean paused) {
        return new ContainerState(sessionId, stage, workflow, ciRetryCount, paused, lastProcessedAt, extraDirs);
    }

    public ContainerState withExtraDirs(List<String> extraDirs) {
        return new ContainerState(sessionId, stage, workflow, ciRetryCount, ciPaused, lastProcessedAt, extraDirs);
    }

    public ContainerState incrementCiRetryCount() {
        return new ContainerState(sessionId, stage, workflow, ciRetryCount + 1, ciPaused, lastProcessedAt, extraDirs);
    }

    public ContainerState resetCi() {
        return new ContainerState(sessionId, stage, workflow, 0, false, lastProcessedAt, extraDirs);
    }

    public ContainerState touch() {
        return new ContainerState(sessionId, stage, workflow, ciRetryCount, ciPaused, Instant.now(), extraDirs);
    }
}
