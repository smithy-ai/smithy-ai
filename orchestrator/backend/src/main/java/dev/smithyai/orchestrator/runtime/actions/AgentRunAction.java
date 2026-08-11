package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.service.claude.PromptRenderer;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * One agent turn, returning its prose.
 *
 * <p>The plan turn runs in Claude's plan permission mode, which is why the mode
 * is an input rather than fixed: a refinement turn must not touch the working
 * tree, while a build turn must.
 */
@Slf4j
@Component
public class AgentRunAction extends AbstractAgentAction {

    public AgentRunAction(RunEnvironments environments, PromptRenderer prompts) {
        super(environments, prompts);
    }

    @Override
    public String type() {
        return "agent.run";
    }

    @Override
    public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
        var agent = agentFor(context, input);
        String prompt = promptFrom(input);

        // Before the turn, not only after: a turn can run for half an hour and
        // the dashboard tails the transcript by session id while it does.
        remember(context, agent);

        if ("plan".equals(optional(input, "mode", "default"))) {
            agent.startPlan(prompt);
            var planFile = agent.latestPlanFile();
            remember(context, agent);
            return Map.of("planFile", planFile.orElse(""), "hasPlan", planFile.isPresent());
        }

        String reply = agent.send(prompt);
        remember(context, agent);
        return Map.of("reply", reply == null ? "" : reply);
    }
}
