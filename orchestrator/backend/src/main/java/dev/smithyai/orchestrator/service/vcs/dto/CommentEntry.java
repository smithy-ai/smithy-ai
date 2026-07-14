package dev.smithyai.orchestrator.service.vcs.dto;

import java.time.OffsetDateTime;

public record CommentEntry(long id, String userLogin, String body, OffsetDateTime createdAt) {}
