package dev.smithyai.orchestrator.service.claude.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The foreman's verdict on a child issue's development plan. When not
 * aligned, feedback is posted verbatim as a comment on the child issue.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChildPlanVerdict(boolean aligned, String feedback) {}
