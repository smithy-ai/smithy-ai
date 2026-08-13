package dev.smithyai.orchestrator.runtime.engine;

import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.actions.ActionContext;
import dev.smithyai.orchestrator.runtime.actions.ActionRegistry;
import dev.smithyai.orchestrator.runtime.definition.WorkflowCompositeActionDefinition;
import dev.smithyai.orchestrator.runtime.definition.WorkflowStepDefinition;
import dev.smithyai.orchestrator.runtime.store.Run;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs a transition's steps in order, persisting each outcome.
 *
 * <p>Transitions here are long: a single agent turn can run for half an hour, so
 * an orchestrator restart mid-transition is normal rather than exceptional.
 * Every step records its outcome, and re-running a transition skips steps that
 * already completed and reuses their recorded output. That is what stops a
 * resume from opening a second pull request.
 *
 * <p>A step declared {@link dev.smithyai.orchestrator.runtime.actions.WorkflowAction#idempotent()}
 * is safe to repeat and is re-run rather than skipped, which keeps its output
 * fresh.
 */
@Slf4j
@Component
public class StepExecutor {

    private final ActionRegistry actions;
    private final ExpressionRenderer renderer;
    private final RunStore store;

    public StepExecutor(ActionRegistry actions, ExpressionRenderer renderer, RunStore store) {
        this.actions = actions;
        this.renderer = renderer;
        this.store = store;
    }

    /**
     * Execute a transition's steps.
     *
     * @param transitionId identifies this transition within the run, so a resume
     *                     can find the steps it already completed. Stable across
     *                     attempts: state plus the triggering event name.
     * @return each step's outputs, keyed by step id
     */
    public Map<String, Map<String, Object>> execute(
        Run run,
        WorkflowEvent event,
        String transitionId,
        List<WorkflowStepDefinition> steps
    ) {
        return execute(run, event, transitionId, steps, Map.of(), Map.of());
    }

    /**
     * @param extraVars variables layered over the run's own, so a {@code foreach}
     *                  can expose {@code item} and {@code index} to its nested
     *                  steps without mutating the run
     */
    public Map<String, Map<String, Object>> execute(
        Run run,
        WorkflowEvent event,
        String transitionId,
        List<WorkflowStepDefinition> steps,
        Map<String, Object> extraVars
    ) {
        return execute(run, event, transitionId, steps, extraVars, Map.of());
    }

    public Map<String, Map<String, Object>> execute(
        Run run,
        WorkflowEvent event,
        String transitionId,
        List<WorkflowStepDefinition> steps,
        Map<String, Object> extraVars,
        Map<String, WorkflowCompositeActionDefinition> composites
    ) {
        // Seed with anything a previous attempt completed, so `steps.<id>` still
        // resolves for steps that are skipped this time round.
        var outputs = new LinkedHashMap<>(store.findStepOutputs(run.id(), transitionId));
        var vars = new LinkedHashMap<>(run.vars());
        vars.putAll(extraVars);

        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            String stepId = step.id() != null && !step.id().isBlank() ? step.id() : step.uses() + "#" + i;
            var context = new ActionContext(run, event, Map.copyOf(outputs), Map.copyOf(vars));

            if (!renderer.isTruthy(step.condition(), context)) {
                log.debug("Run {}: skipping step {} — condition false", run.id(), stepId);
                continue;
            }

            // A step may name a composite the definition declared rather than a
            // registered action: a list of steps under a name, so two transitions
            // can do the same thing without saying it twice.
            var composite = composites.get(step.uses());
            if (composite != null) {
                var scoped = new LinkedHashMap<String, Object>(vars);
                scoped.putAll(renderer.renderInputs(step.with(), context));
                var nested = execute(run, event, transitionId + ":" + stepId, composite.steps(), scoped, composites);
                outputs.put(stepId, Map.of("steps", nested));
                continue;
            }

            var action = actions
                .find(step.uses())
                .orElseThrow(() ->
                    new IllegalStateException("Run %s: no action registered for '%s'".formatted(run.id(), step.uses()))
                );

            if (!action.idempotent() && !store.beginStep(run.id(), transitionId, stepId)) {
                log.info("Run {}: step {} already completed, reusing its output", run.id(), stepId);
                store.findStepOutput(run.id(), transitionId, stepId).ifPresent(prior -> outputs.put(stepId, prior));
                continue;
            }
            if (action.idempotent()) {
                store.beginStep(run.id(), transitionId, stepId);
            }

            try {
                var rendered = renderer.renderInputs(step.with(), context);
                // foreach is the one action that drives the executor, so it is
                // handed its nested steps rather than calling back for them.
                var result =
                    action instanceof ForeachAction foreach
                        ? foreach.executeOver(context, rendered, step.steps(), transitionId + ":" + stepId)
                        : action.execute(context, rendered);
                var stepOutput = result == null ? Map.<String, Object>of() : result;
                store.completeStep(run.id(), transitionId, stepId, stepOutput);
                outputs.put(stepId, stepOutput);
            } catch (RuntimeException e) {
                store.failStep(run.id(), transitionId, stepId, e.getMessage());
                log.error("Run {}: step {} ({}) failed", run.id(), stepId, step.uses(), e);
                throw e;
            }
        }
        return outputs;
    }

    /**
     * A transition's identity within a run.
     *
     * <p>State and event name alone were too coarse: a second comment in the
     * same state looked like the first one being replayed, so every step that
     * had already run was skipped and its recorded output reused — the agent
     * never saw the new comment. The event's own identity separates a new
     * occurrence from a redelivery of the same one.
     */
    public static String transitionId(String state, WorkflowEvent event) {
        String identity = event == null ? "" : event.identity();
        return identity.isEmpty()
            ? state + ":" + event.name()
            : state + ":" + event.name() + ":" + Integer.toHexString(identity.hashCode());
    }
}
