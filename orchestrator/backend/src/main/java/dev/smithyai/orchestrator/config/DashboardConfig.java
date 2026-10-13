package dev.smithyai.orchestrator.config;

/**
 * Where the dashboard is reachable by a person.
 *
 * <p>The orchestrator never needs its own address to work; a workflow does when
 * it wants to point someone at a run — a plan comment inviting the developer
 * to open the agent session and steer it. So this is optional, and the one
 * action that uses it fails with a message naming this setting rather than
 * posting a link to nowhere.
 *
 * @param publicUrl the origin the dashboard is served on, e.g.
 *                  {@code https://smithy.example.com}; a trailing slash is
 *                  tolerated and dropped
 */
public record DashboardConfig(String publicUrl) {
    public static DashboardConfig unconfigured() {
        return new DashboardConfig(null);
    }

    public boolean isConfigured() {
        return publicUrl != null && !publicUrl.isBlank();
    }

    /** The origin without a trailing slash, so a path can be appended directly. */
    public String resolvedPublicUrl() {
        return isConfigured() ? publicUrl.strip().replaceAll("/+$", "") : "";
    }

    /** The dashboard page for one agent session: the container name is the session's address. */
    public String sessionUrl(String containerName) {
        if (!isConfigured()) {
            throw new IllegalStateException(
                "dashboard.publicUrl is not set in orchestrator.yml; it is needed to link to a session"
            );
        }
        return resolvedPublicUrl() + "/session/" + containerName;
    }
}
