package dev.smithyai.orchestrator.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Policy the event adapters need in order to classify a webhook, kept out of
 * the adapters themselves.
 *
 * <p>The adapters used to hardcode {@code "Plan Approved"} and the
 * {@code smithy/} branch prefix, which meant a provider adapter knew about a
 * particular flow and neither could be changed without editing Java. Config is
 * the interim home; once workflows are definitions, these become routing
 * predicates the definition owns.
 */
public record WorkflowPolicyConfig(
    @JsonProperty("plan-approved-label") String planApprovedLabel,
    @JsonProperty("branch-prefix") String branchPrefix,
    @JsonProperty("definitions-dir") String definitionsDir,
    @JsonProperty("engine") Boolean engine
) {
    public static final String DEFAULT_PLAN_APPROVED_LABEL = "Plan Approved";
    public static final String DEFAULT_BRANCH_PREFIX = "smithy/";
    public static final String DEFAULT_DEFINITIONS_DIR = "/config/workflows";

    public static WorkflowPolicyConfig defaults() {
        return new WorkflowPolicyConfig(null, null, null, null);
    }

    /** Where operator-supplied definitions are read from, overriding built-ins by name. */
    public String resolvedDefinitionsDir() {
        return definitionsDir != null && !definitionsDir.isBlank() ? definitionsDir : DEFAULT_DEFINITIONS_DIR;
    }

    /**
     * Whether definitions drive the flows, or the hardcoded Java ones still do.
     *
     * <p>The fallback exists so the ported definitions can be proven against the
     * flows they replace on real work before the Java is deleted, rather than
     * the two being swapped in one commit and the difference discovered later on
     * someone's pull request.
     */
    public boolean engineEnabled() {
        return engine != null && engine;
    }

    public String resolvedPlanApprovedLabel() {
        return planApprovedLabel != null && !planApprovedLabel.isBlank()
            ? planApprovedLabel
            : DEFAULT_PLAN_APPROVED_LABEL;
    }

    public String resolvedBranchPrefix() {
        return branchPrefix != null && !branchPrefix.isBlank() ? branchPrefix : DEFAULT_BRANCH_PREFIX;
    }
}
