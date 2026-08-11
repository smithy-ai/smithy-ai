package dev.smithyai.orchestrator.runtime.engine;

import com.hubspot.jinjava.Jinjava;
import com.hubspot.jinjava.JinjavaConfig;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.actions.ActionContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Renders the {@code {{ ... }}} expressions in a definition.
 *
 * <p>Reuses the Jinjava already used for prompts, and deliberately exposes a
 * small fixed context rather than an evaluator: the point of moving flows into
 * data is that the data stays reviewable. When a definition wants real logic,
 * that is the signal for a new typed action, not a bigger expression language.
 */
@Component
public class ExpressionRenderer {

    private final Jinjava jinjava = new Jinjava(JinjavaConfig.newBuilder().build());

    /** Render one template string against the context. */
    public String render(String template, ActionContext context) {
        if (template == null) return null;
        if (!template.contains("{{") && !template.contains("{%")) return template;
        return jinjava.render(template, contextOf(context));
    }

    /**
     * Render a step's {@code with:} block. Values are rendered recursively so a
     * nested map or list of templates works the same as a top-level one.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> renderInputs(Map<String, Object> inputs, ActionContext context) {
        var rendered = new LinkedHashMap<String, Object>();
        inputs.forEach((key, value) -> rendered.put(key, renderValue(value, context)));
        return rendered;
    }

    @SuppressWarnings("unchecked")
    private Object renderValue(Object value, ActionContext context) {
        return switch (value) {
            case String s -> render(s, context);
            case Map<?, ?> map -> renderInputs((Map<String, Object>) map, context);
            case List<?> list -> {
                var out = new ArrayList<>();
                list.forEach(item -> out.add(renderValue(item, context)));
                yield out;
            }
            case null, default -> value;
        };
    }

    /**
     * Evaluate a step's {@code if:}. Anything that renders to something other
     * than "true" is false — conditions are deliberately not a boolean algebra.
     */
    public boolean isTruthy(String condition, ActionContext context) {
        if (condition == null || condition.isBlank()) return true;
        return "true".equalsIgnoreCase(render(condition, context).strip());
    }

    /** The documented, fixed variable set a definition may reference. */
    public Map<String, Object> contextOf(ActionContext context) {
        var root = new LinkedHashMap<String, Object>();
        root.put("run", runView(context));
        root.put("vars", context.vars());
        root.put("steps", context.steps());
        root.put("event", eventView(context.event()));
        root.put("repo", repoView(context.event()));
        return root;
    }

    private Map<String, Object> runView(ActionContext context) {
        var run = context.run();
        if (run == null) return Map.of();
        return Map.of("id", run.id(), "workflow", run.workflowName(), "state", run.state());
    }

    private Map<String, Object> eventView(WorkflowEvent event) {
        if (event == null) return Map.of();
        var view = new LinkedHashMap<String, Object>();
        view.put("name", event.name());
        switch (event) {
            case WorkflowEvent.IssueScoped e -> {
                view.put("issueRef", e.ctx().issueRef());
                view.put("issueTitle", e.ctx().title());
                view.put("issueBody", e.ctx().body());
                view.put("baseBranch", e.ctx().baseBranch());
            }
            case WorkflowEvent.PrScoped e -> {
                view.put("prNumber", e.prc().number());
                view.put("prTitle", e.prc().title());
                view.put("headBranch", e.prc().headBranch());
                view.put("baseBranch", e.prc().baseBranch());
            }
            default -> {
                // Push and CI events expose only their repo and name.
            }
        }
        return view;
    }

    private Map<String, Object> repoView(WorkflowEvent event) {
        if (event == null || event.info() == null) return Map.of();
        var info = event.info();
        return Map.of(
            "owner",
            info.owner(),
            "name",
            info.repo(),
            "fullName",
            info.owner() + "/" + info.repo(),
            "cloneUrl",
            String.valueOf(info.cloneUrl())
        );
    }
}
