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
 * <p>A step overrides it by naming a connector — {@code connector: gitlab} —
 * which is what a coordinator needs when it creates a child issue somewhere
 * other than where its story lives. Naming the connector rather than a role
 * keeps the definition describing systems instead of topology.
 */
final class Trackers {

    static final String INPUT = "connector";

    private Trackers() {}

    static IssueTrackerClient pick(
        WorkflowAction action,
        ActionContext context,
        Map<String, Object> input,
        IssueTrackers trackers
    ) {
        // A step may name the actor too, though the workflow's own is almost
        // always what is wanted.
        String actor = action.optional(input, "as", context.actor());
        String named = action.optional(input, INPUT, "");
        if (!named.isBlank()) return trackers.forConnector(actor, named);
        return trackers.forConnector(actor, context.event() == null ? "" : context.event().source());
    }
}
