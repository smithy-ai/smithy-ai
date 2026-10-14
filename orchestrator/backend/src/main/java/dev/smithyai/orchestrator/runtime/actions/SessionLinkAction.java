package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.config.DashboardConfig;
import dev.smithyai.orchestrator.runtime.store.RunEnvironment;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * A link to this run's agent session in the dashboard.
 *
 * <p>Where a person goes to take the session over and steer the agent. A plan
 * comment that carries it turns "comment on the story and wait" into "open
 * the session and say what you mean", which is the point of the takeover
 * feature — and the first time a workflow has needed to know where the
 * dashboard lives.
 *
 * <p>Addressed by container name, because that is what the session panel is
 * addressed by. Read from the run's environment record rather than through a
 * session: building a link must not start a stopped container.
 */
@Component
public class SessionLinkAction implements WorkflowAction {

    private final DashboardConfig dashboard;
    private final RunStore store;

    public SessionLinkAction(DashboardConfig dashboard, RunStore store) {
        this.dashboard = dashboard;
        this.store = store;
    }

    @Override
    public String type() {
        return "session.link";
    }

    @Override
    public boolean idempotent() {
        return true;
    }

    @Override
    public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
        if (!dashboard.isConfigured()) {
            throw new IllegalStateException("session.link needs dashboard.publicUrl in orchestrator.yml");
        }
        String container = optional(input, "container", null);
        if (container == null) {
            container = store
                .findEnvironment(context.run().id(), RunEnvironment.CONTAINER)
                .map(RunEnvironment::name)
                .orElseThrow(() ->
                    new IllegalStateException(
                        "session.link: run %s has no container yet; run container.init first or pass 'container'".formatted(
                            context.run().id()
                        )
                    )
                );
        }
        return Map.of("url", dashboard.sessionUrl(container));
    }
}
