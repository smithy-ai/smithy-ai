package dev.smithyai.orchestrator.service.vcs.dto;

import java.util.List;

public record IssueData(
    String issueRef,
    String title,
    String body,
    String state,
    List<String> assignees,
    String baseBranch,
    List<String> labels
) {}
