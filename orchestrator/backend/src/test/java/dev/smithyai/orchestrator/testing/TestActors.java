package dev.smithyai.orchestrator.testing;

import dev.smithyai.orchestrator.config.Actors;
import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;

/** The default identities, for tests that only care that a container is made. */
public final class TestActors {

    private TestActors() {}

    public static Actors defaults() {
        return new Actors(
            new BotConfig(
                new BotConfig.BotEntry("smithy", "smithy@localhost"),
                new BotConfig.BotEntry("architect", "architect@localhost"),
                new BotConfig.BotEntry("coordinator", "coordinator@localhost")
            ),
            new VcsProviderConfig(
                "forgejo",
                null,
                new VcsProviderConfig.ForgejoProviderConfig(
                    "http://forgejo.invalid",
                    null,
                    null,
                    "smithy-token",
                    "architect-token",
                    "coordinator-token"
                ),
                null,
                null,
                null
            )
        );
    }
}
