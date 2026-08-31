package dev.smithyai.orchestrator.design;

import static org.junit.jupiter.api.Assertions.*;

import dev.smithyai.orchestrator.service.design.FigmaLink;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class FigmaLinkTest {

    @Test
    void readsFileAndFrameFromADesignUrl() {
        var links = FigmaLink.scan(
            List.of("Design: https://www.figma.com/design/abc123XYZ789/Checkout?node-id=42-1337&t=xyz")
        );

        assertEquals(1, links.size());
        assertEquals("abc123XYZ789", links.getFirst().fileKey());
        assertEquals("42:1337", links.getFirst().nodeId());
        assertFalse(links.getFirst().isWholeFile());
    }

    /** Older links escape the colon the API wants back. */
    @Test
    void acceptsTheEscapedNodeIdOlderLinksCarry() {
        var links = FigmaLink.scan(List.of("https://www.figma.com/file/abc123XYZ789/Checkout?node-id=42%3A1337"));

        assertEquals("42:1337", links.getFirst().nodeId());
    }

    @Test
    void treatsALinkWithoutAFrameAsTheWholeFile() {
        var links = FigmaLink.scan(List.of("https://www.figma.com/design/abc123XYZ789/Checkout"));

        assertTrue(links.getFirst().isWholeFile());
        assertEquals("", links.getFirst().nodeId());
    }

    @Test
    void findsLinksInsideMarkdownAndJiraMarkup() {
        var links = FigmaLink.scan(
            List.of(
                "See [the design](https://www.figma.com/design/aaaaaaaaaa/Web?node-id=1-2) please.",
                "and [mobile|https://www.figma.com/design/bbbbbbbbbb/App?node-id=3-4]"
            )
        );

        assertEquals(List.of("aaaaaaaaaa", "bbbbbbbbbb"), links.stream().map(FigmaLink::fileKey).toList());
        assertEquals("https://www.figma.com/design/aaaaaaaaaa/Web?node-id=1-2", links.getFirst().url());
    }

    /** A sentence's full stop is not part of the link it ends. */
    @Test
    void dropsTrailingSentencePunctuation() {
        var links = FigmaLink.scan(List.of("Mockup at https://www.figma.com/design/aaaaaaaaaa/Web?node-id=1-2."));

        assertEquals("https://www.figma.com/design/aaaaaaaaaa/Web?node-id=1-2", links.getFirst().url());
    }

    @Test
    void countsTheSameFrameOnceAndTwoFramesTwice() {
        var links = FigmaLink.scan(
            List.of(
                "https://www.figma.com/design/aaaaaaaaaa/Web?node-id=1-2",
                "https://www.figma.com/design/aaaaaaaaaa/Web?node-id=1-2&t=other-session",
                "https://www.figma.com/design/aaaaaaaaaa/Web?node-id=9-9"
            )
        );

        assertEquals(2, links.size());
        assertEquals(List.of("1:2", "9:9"), links.stream().map(FigmaLink::nodeId).toList());
    }

    @Test
    void ignoresTextWithoutDesigns() {
        assertTrue(FigmaLink.scan(Arrays.asList("no designs here", "", null)).isEmpty());
        assertTrue(FigmaLink.scan(List.of("https://example.com/figma.com/design/abc")).isEmpty());
    }

    @Test
    void readsTheOtherSurfacesThatShareTheUrlShape() {
        var links = FigmaLink.scan(
            List.of(
                "https://www.figma.com/proto/cccccccccc/Flow?node-id=1-2",
                "https://www.figma.com/board/dddddddddd/Diagram"
            )
        );

        assertEquals(2, links.size());
    }

    @Test
    void makesNodeIdsSafeForAFilename() {
        assertEquals("42-1337", new FigmaLink("k", "42:1337", "u").nodeSlug());
        assertEquals("I1-2_3-4", new FigmaLink("k", "I1:2;3:4", "u").nodeSlug());
    }
}
