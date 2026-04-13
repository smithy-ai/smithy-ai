package dev.smithyai.orchestrator.model;

public record PrContext(
    RepoInfo info,
    int number,
    String title,
    String body,
    boolean merged,
    String headBranch,
    String baseBranch
) {}
