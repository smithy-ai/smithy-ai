package dev.smithyai.orchestrator.service.vcs.dto;

import java.time.OffsetDateTime;

public record DiscussionNote(
    long id,
    String userLogin,
    String body,
    String path,
    long line,
    OffsetDateTime createdAt
) {}
