package dev.smithyai.orchestrator.runtime.actions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.smithyai.orchestrator.config.DashboardConfig;
import dev.smithyai.orchestrator.runtime.store.Run;
import dev.smithyai.orchestrator.runtime.store.RunEnvironment;
import dev.smithyai.orchestrator.runtime.store.RunStatus;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SessionLinkActionTest {

    private static final Run RUN = new Run(
        "run-7",
        "gm-feature",
        "1",
        RunStatus.RUNNING,
        "new",
        Map.of(),
        null,
        null,
        Instant.now(),
        Instant.now(),
        null
    );

    @Test
    void linksTheRunsOwnContainerUnderThePublicUrl() {
        var store = mock(RunStore.class);
        when(store.findEnvironment("run-7", RunEnvironment.CONTAINER)).thenReturn(
            Optional.of(new RunEnvironment("run-7", RunEnvironment.CONTAINER, "gm.coordinator.ECD-1", Map.of()))
        );
        var action = new SessionLinkAction(new DashboardConfig("https://smithy.example.com/"), store);

        var out = action.execute(new ActionContext(RUN, null, Map.of(), Map.of()), Map.of());

        // The trailing slash in the setting must not double up in the link.
        assertEquals("https://smithy.example.com/session/gm.coordinator.ECD-1", out.get("url"));
    }

    @Test
    void anExplicitContainerWins() {
        var store = mock(RunStore.class);
        var action = new SessionLinkAction(new DashboardConfig("https://smithy.example.com"), store);

        var out = action.execute(new ActionContext(RUN, null, Map.of(), Map.of()), Map.of("container", "other.box"));

        assertEquals("https://smithy.example.com/session/other.box", out.get("url"));
        verifyNoInteractions(store);
    }

    @Test
    void failsNamingTheSettingWhenTheDashboardUrlIsUnset() {
        var action = new SessionLinkAction(DashboardConfig.unconfigured(), mock(RunStore.class));

        var error = assertThrows(IllegalStateException.class, () ->
            action.execute(new ActionContext(RUN, null, Map.of(), Map.of()), Map.of())
        );

        assertTrue(error.getMessage().contains("dashboard.publicUrl"), error.getMessage());
    }

    @Test
    void failsClearlyWhenTheRunHasNoContainer() {
        var store = mock(RunStore.class);
        when(store.findEnvironment(any(), any())).thenReturn(Optional.empty());
        var action = new SessionLinkAction(new DashboardConfig("https://smithy.example.com"), store);

        var error = assertThrows(IllegalStateException.class, () ->
            action.execute(new ActionContext(RUN, null, Map.of(), Map.of()), Map.of())
        );

        assertTrue(error.getMessage().contains("container.init"), error.getMessage());
    }
}
