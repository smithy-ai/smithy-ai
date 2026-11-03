package dev.smithyai.orchestrator.runtime.actions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.VcsClients;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

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
    @ExtendWith(OutputCaptureExtension.class)
    void aThreadTheProviderWillNotResolveIsReportedAndLeftOpen(CapturedOutput output) {
        doThrow(new IllegalStateException("422 not resolvable")).when(vcs).resolvePrDiscussion("acme", "api", 7, "d9");

        var result = new ReviewActions()
            .prResolveAction(vcsClients)
            .execute(CONTEXT, Map.of("owner", "acme", "repo", "api", "number", 7, "discussion", "d9"));

        assertEquals(false, result.get("resolved"));
        assertTrue(output.getOut().contains("acme/api!7"), output.getOut());
        assertTrue(output.getOut().contains("d9"), output.getOut());
        assertTrue(output.getOut().contains("422 not resolvable"), output.getOut());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aBlankDiscussionIsReportedWithoutCallingTheProvider(CapturedOutput output) {
        var result = new ReviewActions()
            .prResolveAction(vcsClients)
            .execute(CONTEXT, Map.of("owner", "acme", "repo", "api", "number", 7, "discussion", " "));

        assertEquals(false, result.get("resolved"));
        verify(vcs, never()).resolvePrDiscussion(anyString(), anyString(), anyInt(), anyString());
        assertTrue(output.getOut().contains("acme/api!7"), output.getOut());
    }

    @Test
    void resolveRequiresTheResolveCapability() {
        assertEquals(Set.of(Capability.DISCUSSION_RESOLVE), new ReviewActions().prResolveAction(vcsClients).requires());
    }
}
