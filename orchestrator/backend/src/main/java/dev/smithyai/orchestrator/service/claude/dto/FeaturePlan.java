package dev.smithyai.orchestrator.service.claude.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The foreman's cross-repo feature plan. Each planned issue targets one
 * manifest project; dependsOn holds indexes into the issues list — an issue
 * is assigned to smithy only once all its dependencies' MRs are merged.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeaturePlan(String summary, List<PlannedIssue> issues, List<String> openQuestions) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlannedIssue(String project, String title, String body, List<Integer> dependsOn) {
        public PlannedIssue {
            if (dependsOn == null) dependsOn = List.of();
        }
    }

    public FeaturePlan {
        if (issues == null) issues = List.of();
        if (openQuestions == null) openQuestions = List.of();
    }
}
