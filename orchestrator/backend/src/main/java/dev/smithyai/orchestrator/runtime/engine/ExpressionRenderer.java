package dev.smithyai.orchestrator.runtime.engine;

import com.hubspot.jinjava.Jinjava;
import com.hubspot.jinjava.JinjavaConfig;
import dev.smithyai.orchestrator.model.CommentData;
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

    {
        // Two text helpers the built-in flows need and that no amount of
        // expression syntax would express readably. Deliberately the only two:
        // a filter that transforms text is not the same thing as logic in a
        // definition, but the line is easy to walk over.
        jinjava.getGlobalContext().registerFilter(new SlugFilter());
        jinjava.getGlobalContext().registerFilter(new DisplayRefFilter());
    }

    /** Turn a title into something usable in a branch name. */
    static final class SlugFilter implements com.hubspot.jinjava.lib.filter.Filter {

        @Override
        public String getName() {
            return "slug";
        }

        @Override
        public Object filter(Object var, com.hubspot.jinjava.interpret.JinjavaInterpreter interpreter, String... args) {
            if (var == null) return "";
            String slug = String.valueOf(var).toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
            return slug.length() > 40 ? slug.substring(0, 40) : slug;
        }
    }

    /**
     * The form of an issue reference a provider auto-links: numeric references
     * take a "#", tracker keys like ECD-4309 are already links and must not.
     */
    static final class DisplayRefFilter implements com.hubspot.jinjava.lib.filter.Filter {

        @Override
        public String getName() {
            return "displayRef";
        }

        @Override
        public Object filter(Object var, com.hubspot.jinjava.interpret.JinjavaInterpreter interpreter, String... args) {
            if (var == null) return "";
            String ref = String.valueOf(var);
            return ref.chars().allMatch(Character::isDigit) ? "#" + ref : ref;
        }
    }

    /** The bindings a foreach exposes to its nested steps. */
    public static final String LOOP_ITEM = "item";
    public static final String LOOP_INDEX = "index";

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

    private static final java.util.regex.Pattern WHOLE_EXPRESSION = java.util.regex.Pattern.compile(
        "^\\s*\\{\\{\\s*([\\w.]+)\\s*}}\\s*$"
    );

    @SuppressWarnings("unchecked")
    private Object renderValue(Object value, ActionContext context) {
        return switch (value) {
            case String s -> {
                // A value that is exactly one expression yields the object it
                // names, not its toString. Without this a `foreach` over
                // "{{ vars.plan }}" would iterate the characters of a rendered
                // list rather than the list.
                var whole = WHOLE_EXPRESSION.matcher(s);
                if (whole.matches()) {
                    Object resolved = resolvePath(whole.group(1), context);
                    if (resolved != null && !(resolved instanceof String)) yield resolved;
                }
                yield render(s, context);
            }
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

    /** Walk a dotted path such as {@code vars.plan} through the context maps. */
    private Object resolvePath(String path, ActionContext context) {
        Object current = contextOf(context);
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(segment);
            if (current == null) return null;
        }
        return current;
    }

    /** The documented, fixed variable set a definition may reference. */
    public Map<String, Object> contextOf(ActionContext context) {
        var root = new LinkedHashMap<String, Object>();
        root.put("run", runView(context));
        root.put("vars", context.vars());
        root.put("steps", context.steps());
        root.put("event", eventView(context.event()));
        root.put("repo", repoView(context.event()));
        // A foreach's bindings read as loop variables rather than as vars.
        if (context.vars().containsKey(LOOP_ITEM)) root.put(LOOP_ITEM, context.vars().get(LOOP_ITEM));
        if (context.vars().containsKey(LOOP_INDEX)) root.put(LOOP_INDEX, context.vars().get(LOOP_INDEX));
        return root;
    }

    private Map<String, Object> runView(ActionContext context) {
        var run = context.run();
        if (run == null) return Map.of();
        return Map.of("id", run.id(), "workflow", run.workflowName(), "state", run.state());
    }

    private Map<String, Object> eventView(WorkflowEvent event) {
        if (event == null) return Map.of();
        // A batch reads as its most recent member, plus the whole burst under
        // `event.batch` for a step that wants to answer all of it at once.
        if (event instanceof WorkflowEvent.Signal signal) {
            // A signal's payload reads as event fields, so a definition can say
            // `event.key` rather than reaching into a map.
            var view = new LinkedHashMap<String, Object>();
            view.put("name", signal.name());
            view.put("signal", signal.signal());
            if (signal.payload() != null) view.putAll(signal.payload());
            return view;
        }
        if (event instanceof WorkflowEvent.Batch batch) {
            var view = new LinkedHashMap<>(eventView(batch.latest()));
            view.put("batch", batch.events().stream().map(this::eventView).toList());
            view.put("batchSize", batch.events().size());
            return view;
        }
        var view = new LinkedHashMap<String, Object>();
        view.put("name", event.name());
        switch (event) {
            case WorkflowEvent.IssueScoped e -> {
                view.put("issueRef", e.ctx().issueRef());
                view.put("issueTitle", e.ctx().title());
                view.put("issueBody", nullToEmpty(e.ctx().body()));
                view.put("baseBranch", nullToEmpty(e.ctx().baseBranch()));
                switch (e) {
                    case WorkflowEvent.IssueComment c -> view.put("commentBody", c.commentBody());
                    case WorkflowEvent.PlanApproved a -> view.put("approver", nullToEmpty(a.approver()));
                    default -> {
                        // Assignment and unassignment carry only the issue.
                    }
                }
            }
            case WorkflowEvent.PrScoped e -> {
                view.put("prNumber", e.prc().number());
                view.put("prTitle", e.prc().title());
                view.put("headBranch", e.prc().headBranch());
                view.put("baseBranch", e.prc().baseBranch());
                // What a review conversation needs to answer in the right place.
                switch (e) {
                    case WorkflowEvent.PrConversationComment c -> {
                        view.put("commentBody", c.commentBody());
                        view.put("commentUser", c.commentUser());
                        view.put("commentId", c.commentId());
                        view.put("discussionId", nullToEmpty(c.discussionId()));
                    }
                    case WorkflowEvent.PrReviewComment c -> {
                        view.put("comments", c.comments().stream().map(CommentData::toMap).toList());
                        view.put("commentId", c.commentId());
                        view.put("discussionId", nullToEmpty(c.discussionId()));
                    }
                    case WorkflowEvent.ReviewSubmitted r -> {
                        view.put("reviewId", r.reviewId());
                        view.put("reviewBody", nullToEmpty(r.reviewBody()));
                        view.put("reviewer", nullToEmpty(r.reviewer()));
                    }
                    default -> {
                        // The rest carry only the pull request itself.
                    }
                }
            }
            case WorkflowEvent.HumanPush e -> view.put("branch", nullToEmpty(e.branch()));
            case WorkflowEvent.CiFailure e -> {
                view.put("branch", nullToEmpty(e.ciRun().headBranch()));
                view.put("workflowId", nullToEmpty(e.workflowName()));
            }
            case WorkflowEvent.CiRecovery e -> view.put("branch", nullToEmpty(e.ciRun().headBranch()));
            default -> {
                // Push and CI events expose only their repo and name.
            }
        }
        return view;
    }

    /** Null renders as the four letters "null", which is never what a template meant. */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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
