package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.VcsClients;
import java.util.Map;

/**
 * Who a repository step acts as.
 *
 * <p>The workflow's own actor by default, so a definition says who it is once
 * rather than on every step. A step overrides it with {@code actor:}, which is
 * what a workflow needs when it acts on behalf of another identity.
 *
 * @see Trackers for the same question about issue trackers
 */
final class Vcs {

    private Vcs() {}

    static VcsClient pick(WorkflowAction action, ActionContext context, Map<String, Object> input, VcsClients clients) {
        String actor = action.optional(input, "actor", context.actor());
        return clients.forConnector(actor, target(action, context, input, clients));
    }

    static String target(WorkflowAction action, ActionContext context, Map<String, Object> input, VcsClients clients) {
        String source = context.event() == null ? "" : context.event().source();
        String target = action.optional(input, "target", "");
        if (target.isBlank() || "event.source".equals(target)) {
            target = clients.hasConnector(source) ? source : clients.defaultConnector();
        }
        return target;
    }
}
