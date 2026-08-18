package dev.smithyai.orchestrator.runtime.engine;

import dev.smithyai.orchestrator.runtime.actions.ActionRegistry;
import dev.smithyai.orchestrator.runtime.actions.Capability;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinition;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionException;
import dev.smithyai.orchestrator.runtime.definition.WorkflowStepDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Checks a definition against what the configured provider can actually do,
 * before it is allowed to run.
 *
 * <p>Provider clients carry {@code default} methods that throw
 * {@code UnsupportedOperationException}, so a workflow needing an unsupported
 * operation used to fail at the moment it ran — mid-flight, on a real issue.
 * Failing at load with a message naming the action and the capability turns
 * that into a startup error a maintainer sees once.
 */
@Component
public class CapabilityValidator {

    private final ActionRegistry actions;

    public CapabilityValidator(ActionRegistry actions) {
        this.actions = actions;
    }

    /**
     * @param supported what the configured provider supports
     * @throws WorkflowDefinitionException if the definition needs anything else,
     *                                     or names an action that does not exist
     */
    public void validate(String source, WorkflowDefinition definition, Set<Capability> supported) {
        var errors = new ArrayList<String>();
        var usedTypes = new ArrayList<String>();
        var composites = definition.actions();
        definition
            .state()
            .getStages()
            .forEach((stageName, stage) ->
                stage.on().forEach((eventName, transition) -> collectSteps(transition.steps(), composites, usedTypes))
            );
        // A composite only reaches here through a transition that uses it, so
        // one declared and never referenced is not held against the definition.

        for (String type : usedTypes) {
            var action = actions.find(type);
            if (action.isEmpty()) {
                errors.add("uses unknown action '%s' (known: %s)".formatted(type, actions.types()));
                continue;
            }
            for (var capability : action.get().requires()) {
                if (!supported.contains(capability)) {
                    errors.add(
                        "action '%s' needs capability '%s', which the configured provider does not support".formatted(
                            type,
                            capability.value()
                        )
                    );
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new WorkflowDefinitionException("Workflow " + source + " cannot run", errors);
        }
    }

    private void collectSteps(
        List<WorkflowStepDefinition> steps,
        java.util.Map<
            String,
            dev.smithyai.orchestrator.runtime.definition.WorkflowCompositeActionDefinition
        > composites,
        List<String> into
    ) {
        for (var step : steps) {
            if (step.uses() == null) continue;
            // A step may name a composite the definition declared rather than a
            // registered action; what needs checking is what the composite does.
            var composite = composites.get(step.uses());
            if (composite != null) {
                collectSteps(composite.steps(), composites, into);
            } else {
                into.add(step.uses());
            }
            collectSteps(step.steps(), composites, into);
        }
    }
}
