package dev.smithyai.orchestrator.web.dto;

import java.time.Instant;

public record InstanceDto(
    String containerName,
    String workflowType,
    String stage,
    Instant lastProcessedAt,
    boolean ciPaused,
    int ciRetryCount,
    boolean running
) {}
