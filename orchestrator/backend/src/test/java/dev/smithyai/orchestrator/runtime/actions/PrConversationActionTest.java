package dev.smithyai.orchestrator.runtime.actions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.VcsClients;
import dev.smithyai.orchestrator.service.vcs.dto.CommentEntry;
import dev.smithyai.orchestrator.service.vcs.dto.DiscussionEntry;
import dev.smithyai.orchestrator.service.vcs.dto.DiscussionNote;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrConversationActionTest {

    private static final String CONNECTOR = "gitlab-main";
    private static final ActionContext CONTEXT = new ActionContext(null, null, Map.of(), Map.of());

    private VcsClient vcs;
    private PrConversationAction action;

    @BeforeEach
    void setUp() {
        vcs = mock(VcsClient.class);
        var vcsClients = new VcsClients(
            Map.of("smithy", Map.of(CONNECTOR, vcs)),
            "smithy",
            CONNECTOR,
            (connector, actor) -> actor + "-bot",
            connector -> "https://git.example"
        );
        action = new PrConversationAction(vcsClients);
    }

    @Test
    void discussionsBecomeOneEntryPerNoteWithThreadAndPosition() {
        var t0 = OffsetDateTime.parse("2026-11-02T10:00:00Z");
        when(vcs.getPrDiscussions("acme", "app", 41)).thenReturn(
            List.of(
                new DiscussionEntry(
                    "d1",
                    true,
                    true,
                    List.of(
                        new DiscussionNote(1, "architect-bot", "Name this", "src/App.java", 12, t0),
                        new DiscussionNote(2, "b.human", "Done in abc123", "src/App.java", 12, t0.plusMinutes(5))
                    )
                ),
                new DiscussionEntry(
                    "d2",
                    false,
                    false,
                    List.of(new DiscussionNote(3, "b.human", "Looks fine overall", "", 0, t0.plusMinutes(1)))
                )
            )
        );

        var output = action.execute(CONTEXT, Map.of("owner", "acme", "repo", "app", "number", 41));

        @SuppressWarnings("unchecked")
        var entries = (List<Map<String, Object>>) output.get("entries");
        assertEquals(3, output.get("count"));
        assertEquals(
            List.of("d1", "d2", "d1"),
            entries
                .stream()
                .map(e -> e.get("discussion"))
                .toList()
        );
        var opening = entries.get(0);
        assertEquals("review_comment", opening.get("type"));
        assertEquals("src/App.java", opening.get("path"));
        assertEquals(12L, opening.get("line"));
        assertEquals(true, opening.get("resolved"));
        assertEquals(false, opening.get("reply"));
        assertEquals(true, entries.get(2).get("reply"));
        assertEquals("comment", entries.get(1).get("type"));
        verify(vcs, never()).getPrComments(any(), any(), anyInt());
    }

    @Test
    void withoutDiscussionsTheCommentsAndReviewsPathIsUnchanged() {
        when(vcs.getPrDiscussions("acme", "app", 41)).thenReturn(List.of());
        when(vcs.getPrComments("acme", "app", 41)).thenReturn(
            List.of(new CommentEntry(1, "b.human", "hi", OffsetDateTime.parse("2026-11-02T10:00:00Z")))
        );

        var output = action.execute(CONTEXT, Map.of("owner", "acme", "repo", "app", "number", 41));

        assertEquals(1, output.get("count"));
    }
}
