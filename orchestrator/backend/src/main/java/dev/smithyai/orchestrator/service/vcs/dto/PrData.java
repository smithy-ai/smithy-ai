package dev.smithyai.orchestrator.service.vcs.dto;

import java.util.List;

public record PrData(
    int number,
    String title,
    String body,
    boolean merged,
    String headRef,
    String baseRef,
    List<String> assignees
) {}
