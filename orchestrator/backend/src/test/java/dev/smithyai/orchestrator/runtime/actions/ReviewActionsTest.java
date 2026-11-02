package dev.smithyai.orchestrator.runtime.actions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.VcsClients;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReviewActionsTest {

    private static final String CONNECTOR = "gitlab-main";
    private static final ActionContext CONTEXT = new ActionContext(null, null, Map.of(), Map.of());

    private VcsClient vcs;
    private VcsClients vcsClients;

    @BeforeEach
    void setUp() {
        vcs = mock(VcsClient.class);
        vcsClients = new VcsClients(
            Map.of("smithy", Map.of(CONNECTOR, vcs)),
            "smithy",
            CONNECTOR,
            (connector, actor) -> actor + "-bot",
            connector -> "https://git.example"
        );
    }

    @Test
    void resolveClosesTheDiscussionOnTheProvider() {
        var output = new ReviewActions()
            .prResolveAction(vcsClients)
            .execute(CONTEXT, Map.of("owner", "acme", "repo", "api", "number", 7, "discussion", "d9"));

        assertEquals(true, output.get("resolved"));
        verify(vcs).resolvePrDiscussion("acme", "api", 7, "d9");
    }

    @Test
    void resolveRequiresTheResolveCapability() {
        assertEquals(Set.of(Capability.DISCUSSION_RESOLVE), new ReviewActions().prResolveAction(vcsClients).requires());
    }
}
