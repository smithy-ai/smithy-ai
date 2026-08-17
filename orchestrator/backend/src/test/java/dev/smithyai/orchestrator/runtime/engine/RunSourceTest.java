package dev.smithyai.orchestrator.runtime.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.smithyai.orchestrator.runtime.store.Run;
import dev.smithyai.orchestrator.runtime.store.RunStatus;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunSourceTest {

    @Test
    void signalRepositoryPreservesConnectorAndProvider() {
        var now = Instant.now();
        var run = new Run(
            "run-1",
            "smithy-development",
            null,
            RunStatus.RUNNING,
            "build",
            Map.of(
                "owner",
                "acme",
                "repo",
                "api",
                RunEngine.SOURCE_VAR,
                "forgejo-main",
                RunEngine.SOURCE_PROVIDER_VAR,
                "forgejo"
            ),
            "parent-1",
            "parent-1",
            now,
            now,
            null
        );

        var info = RunEngine.repoOf(run);

        assertEquals("forgejo-main", info.source());
        assertEquals("forgejo", info.sourceProvider());
    }
}
