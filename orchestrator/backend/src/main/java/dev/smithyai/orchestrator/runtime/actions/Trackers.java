package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import java.util.Map;

/**
 * Which tracker a step means.
 *
 * <p>{@code story} is the configured issue provider — Jira, where stories are
 * tracked that way. {@code repo} is the repository's own issues, which is what a
 * coordinator creates and what an agent working a repository comments on.
 *
 * <p>They are the same system in most deployments and different in exactly the
 * one this exists for, so a step that does not say defaults to the story
 * tracker: that is where its event came from.
 */
final class Trackers {

    static final String INPUT = "tracker";

    private Trackers() {}

    static IssueTrackerClient pick(
        WorkflowAction action,
        Map<String, Object> input,
        IssueTrackerClient story,
        IssueTrackerClient repository
    ) {
        return "repo".equals(action.optional(input, INPUT, "story")) ? repository : story;
    }
}
