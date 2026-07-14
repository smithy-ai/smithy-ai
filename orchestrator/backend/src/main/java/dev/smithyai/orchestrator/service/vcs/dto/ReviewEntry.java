package dev.smithyai.orchestrator.service.vcs.dto;

import java.time.OffsetDateTime;

public record ReviewEntry(
    long id,
    String userLogin,
    String body,
    String state,
    String commitId,
    OffsetDateTime submittedAt
) {}
