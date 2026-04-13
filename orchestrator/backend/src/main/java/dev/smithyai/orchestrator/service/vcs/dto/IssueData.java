package dev.smithyai.orchestrator.service.vcs.dto;

import java.util.List;

public record IssueData(
    int number,
    String title,
    String body,
    String state,
    List<String> assignees,
    String baseBranch,
    List<String> labels
) {}
