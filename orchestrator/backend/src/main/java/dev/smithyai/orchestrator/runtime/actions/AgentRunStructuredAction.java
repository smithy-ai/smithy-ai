package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionException;
import dev.smithyai.orchestrator.runtime.engine.OutputSchema;
import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.service.claude.PromptRenderer;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * An agent turn that must answer in a declared shape.
 *
 * <p>This is what makes a workflow able to act on what the agent decided rather
 * than only on prose: a coordinator's plan comes back as a list of work items a
 * {@code foreach} can iterate, not as text to be parsed. The fields become the
 * step's outputs, so a following step reads {@code steps.plan.tasks} directly.
 */
@Slf4j
@Component
public class AgentRunStructuredAction extends AbstractAgentAction {

    public AgentRunStructuredAction(RunEnvironments environments, PromptRenderer prompts) {
        super(environments, prompts);
    }

    @Override
    public String type() {
        return "agent.runStructured";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
        if (!(input.get("output") instanceof Map<?, ?> declared)) {
            throw new WorkflowDefinitionException("agent.runStructured requires an 'output' shape to answer in");
        }
        String schema = OutputSchema.toJsonSchema((Map<String, Object>) declared);

        var agent = agentFor(context, input);
        remember(context, agent);
        var answer = agent.sendStructured(promptFrom(input), schema);
        remember(context, agent);
        return answer;
    }
}
