package dev.smithyai.orchestrator.runtime.actions;

import static org.junit.jupiter.api.Assertions.*;

import dev.smithyai.orchestrator.service.vcs.IssueTrackers;
import dev.smithyai.orchestrator.service.vcs.dto.CommentEntry;
import dev.smithyai.orchestrator.testing.StubVcsClient;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tidying a planning conversation: the bot's superseded comments go, the
 * approved plan and every human word stay.
 */
class IssuePruneCommentsActionTest {

    private static CommentEntry comment(long id, String author, String body) {
        return new CommentEntry(id, author, body, OffsetDateTime.now());
    }

    /** The coordinator's tracker identity on this connector is "coordinator-bot". */
    private static IssueTrackers trackersFor(StubVcsClient tracker) {
        return new IssueTrackers(
            Map.of("coordinator", Map.of("jira", tracker), "smithy", Map.of("jira", tracker)),
            "smithy",
            "jira",
            (connector, actor) -> actor + "-bot"
        );
    }

    private static Map<String, Object> prune(StubVcsClient tracker, Map<String, Object> input) {
        var action = new IssueActions().issuePruneCommentsAction(trackersFor(tracker));
        var context = new ActionContext(null, null, Map.of(), Map.of(), "coordinator");
        return action.execute(context, input);
    }

    @Test
    void removesTheActorsOwnCommentsExceptTheOnesKept() {
        var tracker = new StubVcsClient();
        tracker.existingIssueComments.addAll(
            List.of(
                comment(1, "coordinator-bot", "On it."),
                comment(2, "coordinator-bot", "Plan v1"),
                comment(3, "alice", "Answer to Q1"),
                comment(4, "coordinator-bot", "Plan v2"),
                comment(5, "smithy-bot", "Someone else's bot")
            )
        );

        var output = prune(tracker, Map.of("owner", "ECD", "repo", "ECD", "issue", "ECD-1", "keep", List.of("4")));

        assertEquals(List.of(1L, 2L), tracker.deletedIssueComments);
        assertEquals(2, output.get("deleted"));
        assertEquals(List.of(4L), output.get("kept"));
        assertEquals(0, output.get("failed"));
        // Humans and other identities are never touched.
        assertTrue(tracker.existingIssueComments.stream().anyMatch(c -> c.id() == 3));
        assertTrue(tracker.existingIssueComments.stream().anyMatch(c -> c.id() == 5));
    }

    @Test
    void keepAcceptsBlanksFromAnUnrenderedStepOutput() {
        var tracker = new StubVcsClient();
        tracker.existingIssueComments.addAll(
            List.of(comment(1, "coordinator-bot", "a"), comment(2, "coordinator-bot", "b"))
        );

        var output = prune(
            tracker,
            Map.of("owner", "ECD", "repo", "ECD", "issue", "ECD-1", "keep", List.of("2", "", " "))
        );

        assertEquals(List.of(1L), tracker.deletedIssueComments);
        assertEquals(List.of(2L), output.get("kept"));
    }

    @Test
    void anExplicitAuthorOverridesTheActorsIdentity() {
        var tracker = new StubVcsClient();
        tracker.existingIssueComments.addAll(
            List.of(comment(1, "coordinator-bot", "a"), comment(2, "legacy-bot", "b"))
        );

        prune(tracker, Map.of("owner", "ECD", "repo", "ECD", "issue", "ECD-1", "author", "legacy-bot"));

        assertEquals(List.of(2L), tracker.deletedIssueComments);
    }

    @Test
    void aRefusedDeletionIsCountedNotThrown() {
        var tracker = new StubVcsClient() {
            @Override
            public void deleteIssueComment(String owner, String repo, String issueRef, long commentId) {
                if (commentId == 1) throw new RuntimeException("Jira API error 403 on DELETE");
                super.deleteIssueComment(owner, repo, issueRef, commentId);
            }
        };
        tracker.existingIssueComments.addAll(
            List.of(comment(1, "coordinator-bot", "a"), comment(2, "coordinator-bot", "b"))
        );

        var output = prune(tracker, Map.of("owner", "ECD", "repo", "ECD", "issue", "ECD-1"));

        assertEquals(1, output.get("failed"));
        assertEquals(1, output.get("deleted"));
        assertEquals(List.of(2L), tracker.deletedIssueComments);
    }

    @Test
    void nothingOfTheActorsIsANoOp() {
        var tracker = new StubVcsClient();
        tracker.existingIssueComments.add(comment(1, "alice", "hello"));

        var output = prune(tracker, Map.of("owner", "ECD", "repo", "ECD", "issue", "ECD-1"));

        assertEquals(0, output.get("deleted"));
        assertTrue(tracker.deletedIssueComments.isEmpty());
    }

    @Test
    void issueCommentsExposesTheIdsPruneNeeds() {
        var tracker = new StubVcsClient();
        tracker.existingIssueComments.add(comment(42, "alice", "hello"));
        var action = new IssueActions().issueCommentsAction(trackersFor(tracker));

        var output = action.execute(
            new ActionContext(null, null, Map.of(), Map.of(), "coordinator"),
            Map.of("owner", "ECD", "repo", "ECD", "issue", "ECD-1")
        );

        @SuppressWarnings("unchecked")
        var comments = (List<Map<String, Object>>) output.get("comments");
        assertEquals(42L, comments.getFirst().get("id"));
        assertEquals("alice", comments.getFirst().get("author"));
    }
}
