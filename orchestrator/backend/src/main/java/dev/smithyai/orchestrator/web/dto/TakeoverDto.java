package dev.smithyai.orchestrator.web.dto;

import java.time.Instant;

/**
 * @param canFinish         whether the run's workflow would act on being handed back
 *                          right now — what decides if the dashboard shows that button
 * @param turnBudgetSeconds how long one human-driven turn may take, so the browser
 *                          waits for the server's budget rather than its own guess
 */
public record TakeoverDto(boolean active, Instant expiresAt, boolean canFinish, long turnBudgetSeconds) {}
