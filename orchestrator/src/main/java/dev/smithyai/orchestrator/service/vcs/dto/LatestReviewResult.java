package dev.smithyai.orchestrator.service.vcs.dto;

import java.util.List;

public record LatestReviewResult(List<ReviewCommentEntry> comments, String reviewBody) {}
