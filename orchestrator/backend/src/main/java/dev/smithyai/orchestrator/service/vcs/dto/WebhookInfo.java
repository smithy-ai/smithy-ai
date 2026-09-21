package dev.smithyai.orchestrator.service.vcs.dto;

import java.util.Set;

/**
 * A webhook a repository has registered, in provider-neutral terms.
 *
 * <p>Each provider names its events differently — GitLab has boolean flags per
 * kind, Forgejo and GitHub list event names — so the clients translate into
 * the handful of kinds the orchestrator routes on. What matters here is not
 * the exact list but whether the things a workflow reacts to can reach it.
 *
 * @param url    where deliveries go
 * @param active whether the provider still delivers to it (a hook that failed
 *               too often is disabled on some providers and delivers nothing)
 * @param events which of the {@code COMMENTS}, {@code PULL_REQUESTS},
 *               {@code ISSUES}, {@code PUSH} and {@code CI} kinds it sends
 */
public record WebhookInfo(String url, boolean active, Set<String> events) {
    /** Comments on issues and pull requests, including review comments. */
    public static final String COMMENTS = "comments";

    /** Pull request opened, updated, merged, closed. */
    public static final String PULL_REQUESTS = "pull_requests";

    public static final String ISSUES = "issues";
    public static final String PUSH = "push";

    /** Pipeline, workflow or check results. */
    public static final String CI = "ci";

    public Set<String> events() {
        return events == null ? Set.of() : events;
    }

    public boolean sends(String kind) {
        return events().contains(kind);
    }
}
