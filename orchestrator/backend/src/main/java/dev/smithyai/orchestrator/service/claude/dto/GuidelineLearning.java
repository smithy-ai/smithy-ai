package dev.smithyai.orchestrator.service.claude.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Outcome of a guideline-learning turn: whether the human's plan feedback
 * contained a durable rule worth documenting in a guidelines repo, and if
 * so which repo was edited and a summary for the merge request.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GuidelineLearning(boolean updated, String repo, String summary) {}
