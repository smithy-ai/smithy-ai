package dev.smithyai.orchestrator.config;

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

    public Actors(BotConfig bots, VcsProviderConfig vcs) {
        this.bots = bots;
        this.vcs = vcs;
    }

    /** The account name, as the provider knows it. */
    public String user(String actor) {
        return switch (named(actor)) {
            case VcsProviderConfig.ARCHITECT -> bots.resolvedArchitectUser();
            case VcsProviderConfig.COORDINATOR -> bots.resolvedCoordinatorUser();
            default -> bots.resolvedSmithyUser();
        };
    }

    /** The address commits are authored from. */
    public String email(String actor) {
        return switch (named(actor)) {
            case VcsProviderConfig.ARCHITECT -> bots.resolvedArchitectEmail();
            case VcsProviderConfig.COORDINATOR -> bots.resolvedCoordinatorEmail();
            default -> bots.resolvedSmithyEmail();
        };
    }

    /**
     * The token it acts with, falling back to the default account's where this
     * actor has none of its own.
     */
    public String token(String actor) {
        return vcs.tokenFor(named(actor));
    }

    private static String named(String actor) {
        return actor == null || actor.isBlank() ? VcsProviderConfig.SMITHY : actor;
    }
}
