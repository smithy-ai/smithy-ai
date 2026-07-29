package dev.smithyai.orchestrator.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Feature-level orchestration agent ("foreman"): plans a story across the
 * repos manifest, creates child issues after human approval, and shepherds
 * them to merged MRs.
 */
public record ForemanConfig(
    boolean enabled,
    @JsonProperty("manifest-path") String manifestPath,
    @JsonProperty("max-issues") Integer maxIssues,
    String autonomy
) {
    public int resolvedMaxIssues() {
        return maxIssues != null && maxIssues > 0 ? maxIssues : 10;
    }

    /** gated: child-plan approval requires a human; auto (default): the foreman approves aligned plans. */
    public boolean isGated() {
        return "gated".equalsIgnoreCase(autonomy);
    }

    public void validate() {
        if (!enabled) return;
        if (manifestPath == null || manifestPath.isBlank()) {
            throw new IllegalStateException("foreman.manifest-path is required when foreman.enabled is true");
        }
    }
}
