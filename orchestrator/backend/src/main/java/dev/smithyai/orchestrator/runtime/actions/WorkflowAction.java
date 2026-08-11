package dev.smithyai.orchestrator.runtime.actions;

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
}
