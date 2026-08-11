package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.service.claude.ClaudeSession;
import dev.smithyai.orchestrator.service.claude.PromptRenderer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Shared machinery for the actions that talk to the agent.
 *
 * <p>Two things every agent step must get right. The prompt comes either
 * inline or from a template file, because the substantial prompts are long
 * enough that inlining them in a definition would bury the flow. And the
 * session id is written back afterwards, so the next transition — possibly
 * after an orchestrator restart — resumes the conversation rather than opening
 * a fresh one that has forgotten everything.
 */
public abstract class AbstractAgentAction implements WorkflowAction {

    protected final RunEnvironments environments;
    protected final PromptRenderer prompts;

    protected AbstractAgentAction(RunEnvironments environments, PromptRenderer prompts) {
        this.environments = environments;
        this.prompts = prompts;
    }

    @Override
    public Set<Capability> requires() {
        return Set.of(Capability.ENVIRONMENT, Capability.AGENT);
    }

    /**
     * An agent turn is never replayed. It is the longest and most expensive step
     * there is, and its side effects are already in the working tree.
     */
    @Override
    public boolean idempotent() {
        return false;
    }

    /** {@code prompt:} inline, or {@code template:} plus its {@code vars:}. */
    @SuppressWarnings("unchecked")
    protected String promptFrom(Map<String, Object> input) {
        Object inline = input.get("prompt");
        if (inline != null && !String.valueOf(inline).isBlank()) return String.valueOf(inline);

        String template = required(input, "template");
        var variables = new LinkedHashMap<String, Object>();
        if (input.get("vars") instanceof Map<?, ?> declared) {
            variables.putAll((Map<String, Object>) declared);
        }
        return prompts.render(template, variables);
    }

    protected ClaudeSession agentFor(ActionContext context, Map<String, Object> input) {
        var agent = environments.agent(context.run(), listInput(input, "tools"));
        String contextRepo = optional(input, "contextRepo", null);
        if (contextRepo != null) agent.setContextRepoName(contextRepo);
        return agent;
    }

    /**
     * Record the session id before the turn as well as after it, so the
     * dashboard can tail a live transcript while a half-hour turn is still
     * running rather than only once it returns.
     */
    protected void remember(ActionContext context, ClaudeSession agent) {
        environments.rememberAgentSession(environments.container(context.run()), agent);
    }
}
