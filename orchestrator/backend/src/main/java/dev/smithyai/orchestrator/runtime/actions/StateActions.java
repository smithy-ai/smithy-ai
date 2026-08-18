package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.runtime.store.RunStore;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Actions that write to the run itself.
 *
 * <p>The old flows kept two copies of where a run had got to — a string written
 * into the container's state file and an in-memory state machine — and they
 * drifted: one wrote {@code "build"} while the other tracked {@code Stage.BUILD}.
 * There is one copy now, in the run store, and these are how a step touches it.
 */
@Slf4j
@Configuration
public class StateActions {

    /**
     * Move the run to another state.
     *
     * <p>A transition's {@code to:} covers the ordinary case; this is for a step
     * that decides where to go — a review that either sends work back or lets it
     * through.
     */
    @Bean
    public WorkflowAction stateSetAction(RunStore store) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "state.set";
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                String state = required(input, "state");
                store.updateState(context.run().id(), state);
                return Map.of("state", state);
            }
        };
    }

    /**
     * Set run variables.
     *
     * <p>Variables are how a workflow remembers anything across transitions —
     * the PR it opened, the review round it is on — and they outlive the
     * container, which is the whole point of the run store.
     */
    @Bean
    public WorkflowAction stateVarAction(RunStore store) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "state.var";
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var updates = new LinkedHashMap<String, Object>(input);
                if (updates.isEmpty()) return Map.of();
                store.mergeVars(context.run().id(), updates);
                return updates;
            }
        };
    }

    /**
     * Record something in the run's history.
     *
     * <p>Metric names come from the definition rather than the platform, because
     * the platform has no business knowing what {@code plan_posted} means.
     */
    @Bean
    public WorkflowAction metricsRecordAction(RunStore store) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "metrics.record";
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                String name = required(input, "name");
                var payload = new LinkedHashMap<String, Object>(input);
                payload.remove("name");
                store.appendEvent(context.run().id(), name, payload);
                return Map.of("recorded", name);
            }
        };
    }
}
