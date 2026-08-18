package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Start the agent's conversation over.
 *
 * <p>Some transitions are a change of job rather than a continuation: planning
 * and building want different tools and a clean head, and carrying half an hour
 * of planning discussion into the build turn wastes context on decisions that
 * are already made. Forgetting the recorded session id is what makes the next
 * agent step open a fresh conversation instead of resuming.
 */
@Slf4j
@Component
public class AgentNewSessionAction implements WorkflowAction {

    private final RunEnvironments environments;

    public AgentNewSessionAction(RunEnvironments environments) {
        this.environments = environments;
    }

    @Override
    public String type() {
        return "agent.newSession";
    }

    @Override
    public Set<Capability> requires() {
        return Set.of(Capability.ENVIRONMENT, Capability.AGENT);
    }

    @Override
    public boolean idempotent() {
        // Clearing an already-cleared session is a no-op; clearing one the next
        // step just created is not, so this must never be replayed blindly —
        // which is exactly what step recording gives it.
        return false;
    }

    @Override
    public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
        var session = environments.container(context.run());
        session.updateState(state -> state.withSessionId(null));
        log.info("Run {} starts a fresh agent session", context.run().id());
        return Map.of("reset", true);
    }
}
