package dev.smithyai.orchestrator.web.dto;

import java.time.Instant;

/**
 * @param canFinish whether the run's workflow would act on being handed back
 *                  right now — what decides if the dashboard shows that button
 */
public record TakeoverDto(boolean active, Instant expiresAt, boolean canFinish) {}
