package dev.smithyai.orchestrator.web.dto;

import dev.smithyai.orchestrator.runtime.store.Run;
import java.time.Instant;
import java.util.List;

/**
 * A run as the dashboard sees it. Unlike {@link InstanceDto}, which only exists
 * while a container does, this is available for finished and failed runs too —
 * the run list is history, not a view of {@code docker ps}.
 */
public record RunDto(
    String id,
    String workflowName,
    String status,
    String state,
    String parentRunId,
    List<String> containers,
    boolean live,
    Instant createdAt,
    Instant updatedAt,
    Instant terminalAt
) {
    public static RunDto from(Run run, List<String> containers, boolean live) {
        return new RunDto(
            run.id(),
            run.workflowName(),
            run.status().value(),
            run.state(),
            run.parentRunId(),
            containers,
            live,
            run.createdAt(),
            run.updatedAt(),
            run.terminalAt()
        );
    }
}
