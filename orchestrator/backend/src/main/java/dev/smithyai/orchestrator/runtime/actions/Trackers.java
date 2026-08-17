package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.IssueTrackers;
import java.util.Map;

/**
 * Which system a step acts against.
 *
 * <p>The event's own connector by default, which is right whenever a workflow
 * answers the thing that triggered it: a Jira story is answered in Jira and a
 * GitLab issue in GitLab, with nothing said in the definition.
 *
 * <p>A step overrides it by naming a connector — {@code target: gitlab-main} —
 * which is what a coordinator needs when it creates a child issue somewhere
 * other than where its story lives. Naming the connector rather than a role
 * keeps the definition describing systems instead of topology.
 */
final class Trackers {

    static final String INPUT = "target";

    private Trackers() {}

    static IssueTrackerClient pick(
        WorkflowAction action,
        ActionContext context,
        Map<String, Object> input,
        IssueTrackers trackers
    ) {
        String actor = action.optional(input, "actor", context.actor());
        return trackers.forConnector(actor, target(action, context, input));
    }

    static String target(WorkflowAction action, ActionContext context, Map<String, Object> input) {
        String eventSource = context.event() == null ? "" : context.event().source();
        String named = action.optional(input, INPUT, "");
        return named.isBlank() || "event.source".equals(named) ? eventSource : named;
    }
}
