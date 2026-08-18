package dev.smithyai.orchestrator.web.dto;

import java.time.Instant;

public record TakeoverDto(boolean active, Instant expiresAt) {}
