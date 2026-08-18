package dev.smithyai.orchestrator.runtime.engine;

import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.actions.ActionContext;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinition;
import dev.smithyai.orchestrator.runtime.definition.WorkflowRoutingAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Decides what an event does, from the definitions rather than from Java.
 *
 * <p>This replaces broadcasting every event to every factory and letting each
 * opt out with a negative check — an arrangement that made one flow's factory
 * import another's just to bow out of its events.
 */
@Slf4j
@Component
public class WorkflowRouter {

    private final ExpressionRenderer renderer;

    public WorkflowRouter(ExpressionRenderer renderer) {
        this.renderer = renderer;
    }

    /** What a matching rule decided: which workflow, which run key, what to do. */
    /**
     * @param by non-null when the run is found through a correlation the run
     *           itself registered, rather than through a rendered key
     */
    public record Decision(String workflowName, WorkflowRoutingAction action, String key, String by) {}

    /**
     * Every decision the given definitions make about this event. A workflow
     * whose rule has a {@code when} predicate that renders false is skipped, so
     * two definitions listening on the same event can be told apart.
     */
    public List<Decision> route(WorkflowEvent event, List<WorkflowDefinition> definitions) {
        var decisions = new ArrayList<Decision>();

        for (var definition : definitions) {
            // The definition's own vars, because a routing rule's whole job can
            // be to ask a question about them — "do I have a repository catalog
            // to fan out to?" — and there is no run yet to read them from.
            var context = new ActionContext(null, event, Map.of(), definition.vars());
            for (var rule : definition.routing()) {
                if (!rule.matchesName(event.name())) continue;
                if (!renderer.isTruthy(rule.when(), context)) {
                    log.debug("{}: rule for {} skipped — predicate false", definition.metadata().name(), event.name());
                    continue;
                }
                if (rule.action() == WorkflowRoutingAction.ignore) break;

                if (rule.by() != null && !rule.by().isBlank()) {
                    decisions.add(new Decision(definition.metadata().name(), rule.action(), null, rule.by()));
                    break;
                }
                String key = renderer.render(rule.key(), context);
                if (key == null || key.isBlank()) {
                    log.warn("{}: rule for {} produced an empty key", definition.metadata().name(), event.name());
                    break;
                }
                decisions.add(new Decision(definition.metadata().name(), rule.action(), key, null));
                // First matching rule per definition wins, so ordering in the
                // file is the arbitration a reader can see.
                break;
            }
        }
        return decisions;
    }
}
