package dev.smithyai.orchestrator.model;

public record IssueContext(RepoInfo info, int number, String title, String body, String baseBranch) {}
