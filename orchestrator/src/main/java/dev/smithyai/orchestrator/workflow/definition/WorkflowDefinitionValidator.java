package dev.smithyai.orchestrator.workflow.definition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class WorkflowDefinitionValidator {

    public void validate(String sourceName, WorkflowDefinition definition) {
        var errors = new ArrayList<String>();
        if (definition == null) {
            errors.add("workflow definition is empty");
            throw new WorkflowDefinitionException(sourceName + " is invalid", errors);
        }

        requireEquals(errors, "apiVersion", definition.apiVersion(), "smithy.ai/v1alpha1");
        requireEquals(errors, "kind", definition.kind(), "Workflow");

        if (definition.metadata() == null || isBlank(definition.metadata().name())) {
            errors.add("metadata.name is required");
        }

        validateRouting(errors, definition);
        validateState(errors, definition);
        validateCompositeActions(errors, definition);

        if (!errors.isEmpty()) {
            throw new WorkflowDefinitionException(sourceName + " is invalid", errors);
        }
    }

    private void validateRouting(ArrayList<String> errors, WorkflowDefinition definition) {
        for (int i = 0; i < definition.routing().size(); i++) {
            var rule = definition.routing().get(i);
            String location = "routing[" + i + "]";
            if (rule.event().isEmpty()) {
                errors.add(location + ".event is required");
            }
            for (String event : rule.event()) {
                if (isBlank(event)) {
                    errors.add(location + ".event contains a blank event name");
                }
            }
            if (rule.action() == null) {
                errors.add(location + ".action is required");
            } else if (rule.action() != WorkflowRoutingAction.ignore && isBlank(rule.key())) {
                errors.add(location + ".key is required for " + rule.action());
            }
        }
    }

    private void validateState(ArrayList<String> errors, WorkflowDefinition definition) {
        var state = definition.state();
        if (state == null) {
            errors.add("state is required");
            return;
        }

        if (isBlank(state.getInitial())) {
            errors.add("state.initial is required");
        }

        var stages = state.getStages();
        if (stages.isEmpty()) {
            errors.add("state must define at least one stage");
            return;
        }

        if (!isBlank(state.getInitial()) && !stages.containsKey(state.getInitial())) {
            errors.add("state.initial references unknown stage " + state.getInitial());
        }
        if (!isBlank(state.getTerminal()) && !stages.containsKey(state.getTerminal())) {
            errors.add("state.terminal references unknown stage " + state.getTerminal());
        }

        for (var stageEntry : stages.entrySet()) {
            String stageName = stageEntry.getKey();
            var stage = stageEntry.getValue();
            if (stage == null) {
                errors.add("state." + stageName + " must be an object");
                continue;
            }
            for (var transitionEntry : stage.on().entrySet()) {
                String event = transitionEntry.getKey();
                var transition = transitionEntry.getValue();
                String location = "state." + stageName + ".on." + event;
                if (transition == null) {
                    errors.add(location + " must be an object");
                    continue;
                }
                if (!isBlank(transition.to()) && !stages.containsKey(transition.to())) {
                    errors.add(location + ".to references unknown stage " + transition.to());
                }
                validateSteps(errors, location + ".steps", transition.steps());
            }
        }
    }

    private void validateCompositeActions(ArrayList<String> errors, WorkflowDefinition definition) {
        for (var entry : definition.actions().entrySet()) {
            if (isBlank(entry.getKey())) {
                errors.add("actions contains a blank action name");
            }
            var action = entry.getValue();
            if (action == null) {
                errors.add("actions." + entry.getKey() + " must be an object");
                continue;
            }
            validateSteps(errors, "actions." + entry.getKey() + ".steps", action.steps());
        }
    }

    private void validateSteps(
        ArrayList<String> errors,
        String location,
        java.util.List<WorkflowStepDefinition> steps
    ) {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            String stepLocation = location + "[" + i + "]";
            if (step == null) {
                errors.add(stepLocation + " must be an object");
                continue;
            }
            if (isBlank(step.uses())) {
                errors.add(stepLocation + ".uses is required");
            }
            if (!isBlank(step.id()) && !ids.add(step.id())) {
                errors.add(stepLocation + ".id duplicates another step id in this transition");
            }
        }
    }

    private void requireEquals(ArrayList<String> errors, String field, String actual, String expected) {
        if (!expected.equals(actual)) {
            errors.add(field + " must be " + expected);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
