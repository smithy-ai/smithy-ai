package dev.smithyai.orchestrator.service.vcs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The issue trackers this deployment can reach, by actor and connector.
 *
 * <p>Two questions, and they are separate. <em>Which system</em> — a story in
 * Jira is answered in Jira, a repository issue in the repository's own tracker.
 * <em>As whom</em> — a coordinator that plans a feature and an agent that
 * implements one task of it are different accounts, and a reader of the issue
 * should be able to tell which of them wrote something.
 *
 * <p>An actor with no identity of its own falls back to the default one. That
 * keeps a single-account deployment working, at the cost of everything being
 * attributed to that account.
 */
public class IssueTrackers {

    private final Map<String, Map<String, IssueTrackerClient>> byActor;
    private final String defaultActor;
    private final String defaultConnector;
    private final IssueTrackerClient fallback;
    private final java.util.function.BiFunction<String, String, String> assigneeResolver;

    public IssueTrackers(
        Map<String, Map<String, IssueTrackerClient>> byActor,
        String defaultActor,
        String defaultConnector,
        IssueTrackerClient fallback,
        java.util.function.BiFunction<String, String, String> assigneeResolver
    ) {
        this.byActor = new LinkedHashMap<>(byActor);
        this.defaultActor = defaultActor;
        this.defaultConnector = defaultConnector;
        this.fallback = fallback;
        this.assigneeResolver = assigneeResolver;
    }

    public IssueTrackers(
        Map<String, Map<String, IssueTrackerClient>> byActor,
        String defaultActor,
        IssueTrackerClient fallback
    ) {
        this(byActor, defaultActor, "", fallback, (connector, actor) -> actor);
    }

    /** Single-actor deployments, and tests. */
    public IssueTrackers(Map<String, IssueTrackerClient> byConnector, IssueTrackerClient fallback) {
        this(
            Map.of("smithy", byConnector),
            "smithy",
            byConnector.keySet().stream().findFirst().orElse(""),
            fallback,
            (connector, actor) -> actor
        );
    }

    /**
     * @param actor     who to act as; unknown or blank means the default
     * @param connector which system; blank means the event's own, resolved by
     *                  the caller, and unknown is a mistake worth reporting
     */
    public IssueTrackerClient forConnector(String actor, String connector) {
        var connectors = byActor.get(actor == null || actor.isBlank() ? defaultActor : actor);
        if (connectors == null) connectors = byActor.getOrDefault(defaultActor, Map.of());

        if (connector == null || connector.isBlank()) {
            return connectors.getOrDefault(defaultConnector, fallback);
        }
        var tracker = connectors.get(connector);
        if (tracker == null) {
            throw new IllegalArgumentException(
                "No connector named '%s' is configured; available: %s".formatted(connector, connectors.keySet())
            );
        }
        return tracker;
    }

    public IssueTrackerClient forConnector(String connector) {
        return forConnector(defaultActor, connector);
    }

    public Set<String> actors() {
        return byActor.keySet();
    }

    public Optional<IssueTrackerClient> find(String actor, String connector) {
        var connectors = byActor.get(actor);
        return connectors == null ? Optional.empty() : Optional.ofNullable(connectors.get(connector));
    }

    public String assignee(String connector, String actor) {
        return assigneeResolver.apply(connector == null || connector.isBlank() ? defaultConnector : connector, actor);
    }
}
