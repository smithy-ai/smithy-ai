package dev.smithyai.orchestrator.service.claude.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The foreman's response to a story comment while the feature is executing:
 * a reply to post on the story, plus optionally new child issues extending
 * the feature plan. dependsOn in a new issue indexes the COMBINED issue
 * list — the existing plan issues first, then the new ones, zero-based.
 * reposNeeded lists manifest projects the foreman needs cloned into its
 * workspace before it can ground the new issues; when non-empty the issues
 * list is expected to be empty and the request is retried after cloning.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeatureExtension(String reply, List<FeaturePlan.PlannedIssue> issues, List<String> reposNeeded) {
    public FeatureExtension {
        if (issues == null) issues = List.of();
        if (reposNeeded == null) reposNeeded = List.of();
    }
}
