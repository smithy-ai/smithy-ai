package dev.smithyai.orchestrator.service.vcs.dto;

import java.time.OffsetDateTime;

public record ReviewCommentEntry(String userLogin, String body, String path, long position, OffsetDateTime createdAt) {}
