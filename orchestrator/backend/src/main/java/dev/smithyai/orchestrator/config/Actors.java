package dev.smithyai.orchestrator.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * What each machine identity is called, and what it authenticates with.
 *
 * <p>A workflow says which actor it acts as; this answers the rest. Without it
 * every container would be handed the default account's token, and work an
 * architect did would be signed by the agent it was reviewing.
 */
@Component
public class Actors {

    private final BotConfig bots;
    private final VcsProviderConfig vcs;
    private final ConnectorRegistry connectors;

    @Autowired
    public Actors(ConnectorRegistry connectors) {
        this.bots = null;
        this.vcs = null;
        this.connectors = connectors;
    }

    public Actors(BotConfig bots, VcsProviderConfig vcs) {
        this.bots = bots;
        this.vcs = vcs;
        this.connectors = null;
    }

    /** The account name, as the provider knows it. */
    public String user(String actor) {
        if (connectors != null) return connectors.username(connectors.defaultVcs(), actor);
        return switch (named(actor)) {
            case VcsProviderConfig.ARCHITECT -> bots.resolvedArchitectUser();
            case VcsProviderConfig.COORDINATOR -> bots.resolvedCoordinatorUser();
            default -> bots.resolvedSmithyUser();
        };
    }

    /** The address commits are authored from. */
    public String email(String actor) {
        if (connectors != null) return connectors.gitEmail(connectors.defaultVcs(), actor);
        return switch (named(actor)) {
            case VcsProviderConfig.ARCHITECT -> bots.resolvedArchitectEmail();
            case VcsProviderConfig.COORDINATOR -> bots.resolvedCoordinatorEmail();
            default -> bots.resolvedSmithyEmail();
        };
    }

    /** The token this actor uses on the default VCS connector. */
    public String token(String actor) {
        if (connectors != null) return connectors.token(connectors.defaultVcs(), actor);
        return vcs.tokenFor(named(actor));
    }

    public String user(String connector, String actor) {
        return connectors == null ? user(actor) : connectors.username(connector, actor);
    }

    public String email(String connector, String actor) {
        return connectors == null ? email(actor) : connectors.gitEmail(connector, actor);
    }

    public String gitName(String connector, String actor) {
        return connectors == null ? user(actor) : connectors.gitName(connector, actor);
    }

    public String token(String connector, String actor) {
        return connectors == null ? token(actor) : connectors.token(connector, actor);
    }

    public String vcsUrl(String connector) {
        return connectors == null ? vcs.resolvedUrl() : connectors.connector(connector).url();
    }

    public String gitAuthUser(String connector) {
        return connectors == null ? vcs.gitAuthUser() : connectors.gitAuthUser(connector);
    }

    public String vcsConnector(String eventSource) {
        if (connectors == null) return vcs.resolvedProvider();
        if (eventSource != null && !eventSource.isBlank() && connectors.vcsConnectorIds().contains(eventSource)) {
            return eventSource;
        }
        return connectors.defaultVcs();
    }

    private static String named(String actor) {
        return actor == null || actor.isBlank() ? VcsProviderConfig.SMITHY : actor;
    }
}
