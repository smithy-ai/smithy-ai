package dev.smithyai.orchestrator.design;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.smithyai.orchestrator.service.design.FigmaClient;
import dev.smithyai.orchestrator.service.design.FigmaDesignHelper;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.dto.IssueData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FigmaDesignHelperTest {

    private static final String FILE = "abc123XYZ789";
    private static final String FRAME_URL = "https://www.figma.com/design/" + FILE + "/Checkout?node-id=42-1337";

    private final IssueTrackerClient tracker = mock(IssueTrackerClient.class);
    private final FigmaClient figma = mock(FigmaClient.class);
    private final ContainerSession session = mock(ContainerSession.class);

    @BeforeEach
    void setUp() {
        when(figma.isActive()).thenReturn(true);
        when(figma.format()).thenReturn("png");
        when(session.ensureScratchDir(anyString())).thenReturn(true);
        when(session.getContainerName()).thenReturn("smithy-run-1");
        when(tracker.getIssueComments(any(), any(), any())).thenReturn(List.of());
        when(tracker.getIssueLinks(any(), any(), any())).thenReturn(List.of());
    }

    private void issueBody(String body) {
        when(tracker.getIssue(any(), any(), any())).thenReturn(
            new IssueData("ECD-1", "A ticket", body, "open", List.of(), "", List.of())
        );
    }

    private List<Map<String, Object>> fetch() {
        return FigmaDesignHelper.fetchAndInject(tracker, figma, session, "acme", "web", "ECD-1", 10);
    }

    @Test
    void rendersAFrameLinkedFromTheDescriptionIntoTheContainer() {
        issueBody("Build this: " + FRAME_URL);
        when(figma.names(FILE, List.of("42:1337"))).thenReturn(
            new FigmaClient.Names("Checkout", Map.of("42:1337", "Payment modal"))
        );
        when(figma.renderUrls(FILE, List.of("42:1337"))).thenReturn(Map.of("42:1337", "https://s3/render.png"));
        when(figma.download("https://s3/render.png")).thenReturn(new byte[] { 1, 2, 3 });

        var designs = fetch();

        assertEquals(1, designs.size());
        assertEquals(FigmaDesignHelper.DESIGNS_DIR + "/payment-modal-42-1337.png", designs.getFirst().get("path"));
        assertEquals("Payment modal", designs.getFirst().get("frame"));
        assertEquals("Checkout", designs.getFirst().get("file"));
        assertEquals(FRAME_URL, designs.getFirst().get("url"));
        verify(session).copyToContainer(
            FigmaDesignHelper.DESIGNS_DIR,
            new byte[] { 1, 2, 3 },
            "payment-modal-42-1337.png"
        );
    }

    /** A design linked through the Figma app is a remote link, not description text. */
    @Test
    void findsADesignThatOnlyAppearsAsARemoteLink() {
        issueBody("No links in the text.");
        when(tracker.getIssueLinks("acme", "web", "ECD-1")).thenReturn(List.of(FRAME_URL));
        when(figma.names(FILE, List.of("42:1337"))).thenReturn(
            new FigmaClient.Names("Checkout", Map.of("42:1337", "Payment modal"))
        );
        when(figma.renderUrls(FILE, List.of("42:1337"))).thenReturn(Map.of("42:1337", "https://s3/render.png"));
        when(figma.download(anyString())).thenReturn(new byte[] { 1 });

        assertEquals(1, fetch().size());
    }

    @Test
    void rendersThePagesFramesForALinkThatNamedNoFrame() {
        issueBody("https://www.figma.com/design/" + FILE + "/Checkout");
        when(figma.topLevelFrames(eq(FILE), anyInt())).thenReturn(List.of("1:1", "1:2"));
        when(figma.names(FILE, List.of("1:1", "1:2"))).thenReturn(
            new FigmaClient.Names("Checkout", Map.of("1:1", "Empty", "1:2", "Filled"))
        );
        when(figma.renderUrls(FILE, List.of("1:1", "1:2"))).thenReturn(
            Map.of("1:1", "https://s3/a.png", "1:2", "https://s3/b.png")
        );
        when(figma.download(anyString())).thenReturn(new byte[] { 1 });

        var designs = fetch();

        assertEquals(
            List.of("Empty", "Filled"),
            designs
                .stream()
                .map(d -> d.get("frame"))
                .toList()
        );
    }

    @Test
    void stopsAtTheConfiguredLimit() {
        issueBody("https://www.figma.com/design/" + FILE + "/Checkout");
        when(figma.topLevelFrames(eq(FILE), anyInt())).thenAnswer(call -> {
            int limit = call.getArgument(1);
            return List.of("1:1", "1:2", "1:3", "1:4").subList(0, Math.min(limit, 4));
        });
        when(figma.names(eq(FILE), anyList())).thenReturn(new FigmaClient.Names("Checkout", Map.of()));
        when(figma.renderUrls(eq(FILE), anyList())).thenReturn(
            Map.of("1:1", "https://s3/a.png", "1:2", "https://s3/b.png")
        );
        when(figma.download(anyString())).thenReturn(new byte[] { 1 });

        var designs = FigmaDesignHelper.fetchAndInject(tracker, figma, session, "acme", "web", "ECD-1", 2);

        assertEquals(2, designs.size());
    }

    @Test
    void doesNothingWhenNoTokenWasConfigured() {
        when(figma.isActive()).thenReturn(false);
        issueBody(FRAME_URL);

        assertTrue(fetch().isEmpty());
        verifyNoInteractions(session);
    }

    @Test
    void doesNothingWhenTheTicketLinksNoDesign() {
        issueBody("Just a description.");

        assertTrue(fetch().isEmpty());
        verifyNoInteractions(session);
    }

    /** A design that will not render is one the agent works without. */
    @Test
    void survivesAFileItCannotRender() {
        issueBody(FRAME_URL);
        when(figma.names(any(), anyList())).thenThrow(new IllegalStateException("403"));

        assertTrue(fetch().isEmpty());
    }

    @Test
    void namesFilesAfterTheirFrame() {
        assertEquals("payment-modal-42-1337.png", FigmaDesignHelper.filenameFor("Payment Modal", "42:1337", "png"));
        assertEquals("design-1-2.svg", FigmaDesignHelper.filenameFor("", "1:2", "svg"));
        assertEquals("checkout-step-2-3-4.png", FigmaDesignHelper.filenameFor("Checkout / step 2", "3:4", "png"));
    }
}
