package dev.smithyai.orchestrator.runtime.actions;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One typed side effect a workflow step can perform.
 *
 * <p>This is the extension point: adding a capability to the platform means
 * adding an action bean, not editing the engine. Implementations are discovered
 * as Spring beans and looked up by {@link #type()}, which is what a step's
 * {@code uses:} names.
 */
public interface WorkflowAction {
    /** The name a step's {@code uses:} refers to, e.g. {@code issue.comment}. */
    String type();

    /**
     * Run the step. Inputs are the step's {@code with:} block, already rendered
     * through the expression context.
     *
     * @return outputs, addressable downstream as {@code steps.<id>.<key>}
     */
    Map<String, Object> execute(ActionContext context, Map<String, Object> input);

    /** What a provider must support for this action to be usable. */
    default Set<Capability> requires() {
        return Set.of();
    }

    /**
     * Whether re-running this step with the same inputs is harmless.
     *
     * <p>A transition can be interrupted — a 30-minute agent turn inside one is
     * normal here — and resumes at the first incomplete step. Non-idempotent
     * actions are checked against their recorded output before re-running so a
     * resume does not open a second pull request.
     */
    default boolean idempotent() {
        return false;
    }

    // ── Reading a step's `with:` block ───────────────────────
    //
    // A definition is data, so a missing or misspelled key is the most common
    // authoring mistake. These fail naming both the action and the key, because
    // the alternative is a NullPointerException from inside a provider client.

    default String required(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(type() + " requires '" + key + "'");
        }
        return String.valueOf(value);
    }

    default String optional(Map<String, Object> input, String key, String fallback) {
        Object value = input.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    default int intInput(Map<String, Object> input, String key, int fallback) {
        Object value = input.get(key);
        if (value == null) return fallback;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "%s expects a number for '%s', got '%s'".formatted(type(), key, value),
                e
            );
        }
    }

    default boolean boolInput(Map<String, Object> input, String key, boolean fallback) {
        Object value = input.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean bool) return bool;
        return Boolean.parseBoolean(String.valueOf(value).strip());
    }

    /** A list input, tolerating the single value a definition often writes instead. */
    default List<String> listInput(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null) return List.of();
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        String single = String.valueOf(value).strip();
        return single.isEmpty() ? List.of() : List.of(single);
    }

    default List<String> requiredListInput(Map<String, Object> input, String key) {
        var values = listInput(input, key);
        if (values.isEmpty()) throw new IllegalArgumentException(type() + " requires '" + key + "'");
        return values;
    }
}
