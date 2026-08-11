package dev.smithyai.orchestrator.runtime.engine;

import dev.smithyai.orchestrator.runtime.actions.ActionContext;
import dev.smithyai.orchestrator.runtime.actions.WorkflowAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Run nested steps once per item — the fan-out primitive.
 *
 * <p>The definition schema this was ported from had a flat step list and named
 * parallel execution an explicit non-goal, which is exactly why a coordinator
 * could not be expressed in it. Iteration is sequential here; the items are
 * addressable as {@code item} and {@code index} inside the nested steps.
 *
 * <p>Lives in the engine package rather than with the other actions because it
 * is the one action that drives the executor, and takes it lazily to break the
 * cycle that creates.
 */
@Component
public class ForeachAction implements WorkflowAction {

    private final StepExecutor executor;

    public ForeachAction(@Lazy StepExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String type() {
        return "foreach";
    }

    @Override
    public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
        throw new UnsupportedOperationException(
            "foreach is executed by the step executor, which supplies its nested steps"
        );
    }

    /**
     * Execute the nested steps once per item.
     *
     * @param transitionId the enclosing transition, extended per item so each
     *                     iteration records its own steps and a resume skips the
     *                     items already done
     */
    public Map<String, Object> executeOver(
        ActionContext context,
        Map<String, Object> input,
        List<dev.smithyai.orchestrator.runtime.definition.WorkflowStepDefinition> nested,
        String transitionId
    ) {
        Object items = input.get("items");
        var list = items instanceof List<?> l ? l : List.of();
        var results = new ArrayList<Map<String, Map<String, Object>>>();

        for (int i = 0; i < list.size(); i++) {
            // Each iteration gets its own transition id, so a resume skips the
            // items already done rather than repeating their side effects.
            results.add(
                executor.execute(
                    context.run(),
                    context.event(),
                    transitionId + "[" + i + "]",
                    nested,
                    Map.of("item", list.get(i), "index", i)
                )
            );
        }
        return Map.of("count", list.size(), "results", results);
    }
}
