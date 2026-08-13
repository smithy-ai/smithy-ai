package dev.smithyai.orchestrator.service.vcs;

import dev.smithyai.orchestrator.model.RepoInfo;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The issue trackers this deployment can reach, by connector name.
 *
 * <p>An action works against the system its event came from, which is a
 * question of which connector — not of whether something is a "story" or a
 * "repository issue". Those were roles, and a role is an interpretation the
 * platform has no business making: a Jira ticket is a story in one deployment
 * and the only tracker there is in another.
 */
public class IssueTrackers {

    private final Map<String, IssueTrackerClient> byConnector;
    private final IssueTrackerClient fallback;

    public IssueTrackers(Map<String, IssueTrackerClient> byConnector, IssueTrackerClient fallback) {
        this.byConnector = new LinkedHashMap<>(byConnector);
        this.fallback = fallback;
    }

    /**
     * The tracker for a connector, or the configured one where the name is
     * empty or unknown.
     *
     * @param connector an explicit connector name, or empty to take the
     *                  event's own — which is right whenever a workflow acts on
     *                  the thing that triggered it
     */
    public IssueTrackerClient forConnector(String connector) {
        if (connector == null || connector.isBlank()) return fallback;
        var tracker = byConnector.get(connector);
        return tracker != null ? tracker : fallback;
    }

    /** Whether a named connector is actually configured here. */
    public boolean has(String connector) {
        return connector != null && byConnector.containsKey(connector);
    }

    public Optional<IssueTrackerClient> find(String connector) {
        return Optional.ofNullable(byConnector.get(connector));
    }

    public java.util.Set<String> connectors() {
        return byConnector.keySet();
    }

    /** Connector names this deployment knows about, for messages. */
    public static String describe(RepoInfo info) {
        return info == null || info.source().isBlank() ? "unknown" : info.source();
    }
}
