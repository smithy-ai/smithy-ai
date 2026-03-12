package dev.smithyai.orchestrator.service.vcs.dto;

public record InlineComment(String path, String body, long newPosition) {}
