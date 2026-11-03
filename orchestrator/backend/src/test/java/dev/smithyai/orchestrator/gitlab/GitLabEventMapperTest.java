package dev.smithyai.orchestrator.gitlab;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.config.WorkflowPolicyConfig;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.web.GitLabEventMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitLabEventMapperTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void anIssueOpenedAgainstAnActorSaysWhichOne() throws Exception {
        var toAgent = mapper().map("Issue Hook", json.readTree(opened("smithy-bot")));
        var agent = assertInstanceOf(WorkflowEvent.IssueAssigned.class, toAgent);
        assertEquals("smithy", agent.ctx().assignee());
        assertEquals("gitlab-main", agent.ctx().info().source());
        assertEquals("gitlab", agent.ctx().info().sourceProvider());

        var toCoordinator = mapper().map("Issue Hook", json.readTree(opened("coordinator-bot")));
        assertEquals("coordinator", ((WorkflowEvent.IssueAssigned) toCoordinator).ctx().assignee());

        assertNull(mapper().map("Issue Hook", json.readTree(opened("someone-else"))));
    }

    @Test
    void beingHandedAnIssueLaterSaysWhoItWasHandedTo() throws Exception {
        String payload = """
            {
              "object_kind": "issue",
              "project": {"path_with_namespace": "acme/app", "git_http_url": "https://gitlab.invalid/acme/app.git",
                          "web_url": "https://gitlab.invalid/acme/app"},
              "object_attributes": {"iid": 7, "title": "A thing", "description": "", "action": "update",
                                    "state": "opened"},
              "assignees": [{"username": "coordinator-bot"}],
              "changes": {"assignees": {"previous": [], "current": [{"username": "coordinator-bot"}]}}
            }
            """;

        var event = mapper().map("Issue Hook", json.readTree(payload));

        assertEquals("coordinator", assertInstanceOf(WorkflowEvent.IssueAssigned.class, event).ctx().assignee());
    }

    @Test
    void aDiffNoteOnAnyMergeRequestIsAReviewComment() throws Exception {
        for (String branch : List.of("FVS-1608", "smithy/7-a-thing")) {
            var review = assertInstanceOf(
                WorkflowEvent.PrReviewComment.class,
                mapper().map("Note Hook", json.readTree(diffNote(branch)))
            );
            assertEquals(41, review.prc().number());
            assertEquals("please fix", review.comments().getFirst().body());
            assertEquals(List.of(), review.commentActors());
        }
    }

    @Test
    void aDiffNoteOnADeletedLineCarriesTheOldPathAndLine() throws Exception {
        String payload = diffNote("feature/x").replace(
            "{\"new_path\": \"src/App.java\", \"new_line\": 12}",
            "{\"old_path\": \"src/Gone.java\", \"old_line\": 7}"
        );

        var review = assertInstanceOf(
            WorkflowEvent.PrReviewComment.class,
            mapper().map("Note Hook", json.readTree(payload))
        );

        assertEquals("src/Gone.java", review.comments().getFirst().path());
        assertEquals(7, review.comments().getFirst().line());
    }

    @Test
    void aPlainNoteOnAHumanBranchIsAConversationComment() throws Exception {
        var event = mapper().map("Note Hook", json.readTree(plainNote("FVS-1608", "b.human")));
        var comment = assertInstanceOf(WorkflowEvent.PrConversationComment.class, event);
        assertEquals("d1", comment.discussionId());
        assertEquals(List.of(), comment.commentActors());
    }

    @Test
    void aNoteByABotCarriesItsActor() throws Exception {
        var event = mapper().map("Note Hook", json.readTree(plainNote("FVS-1608", "smithy-bot")));
        assertEquals(
            List.of("smithy"),
            assertInstanceOf(WorkflowEvent.PrConversationComment.class, event).commentActors()
        );
        var diff = mapper().map("Note Hook", json.readTree(diffNote("FVS-1608").replace("b.human", "architect-bot")));
        assertEquals(List.of("architect"), assertInstanceOf(WorkflowEvent.PrReviewComment.class, diff).commentActors());
    }

    @Test
    void anIssueAssignedToAnAccountSeveralActorsShareGoesToTheSameActorEveryTime() throws Exception {
        var shared = mapper(
            new BotConfig(
                new BotConfig.BotEntry("shared-bot", "shared@example.com"),
                new BotConfig.BotEntry("architect-bot", "architect@example.com"),
                new BotConfig.BotEntry("shared-bot", "shared@example.com")
            )
        );
        // Several times, because the actor map has no iteration order to rely on.
        for (int i = 0; i < 5; i++) {
            var event = shared.map("Issue Hook", json.readTree(opened("shared-bot")));
            assertEquals("smithy", assertInstanceOf(WorkflowEvent.IssueAssigned.class, event).ctx().assignee());
        }
    }

    @Test
    void aNoteByAnAccountSeveralActorsShareCarriesEveryOneOfThem() throws Exception {
        var shared = mapper(
            new BotConfig(
                new BotConfig.BotEntry("shared-bot", "shared@example.com"),
                new BotConfig.BotEntry("architect-bot", "architect@example.com"),
                new BotConfig.BotEntry("shared-bot", "shared@example.com")
            )
        );
        // Several times, because the actor map has no iteration order to rely on.
        for (int i = 0; i < 5; i++) {
            var note = shared.map("Note Hook", json.readTree(plainNote("feature/x", "shared-bot")));
            assertEquals(
                List.of("smithy", "coordinator"),
                assertInstanceOf(WorkflowEvent.PrConversationComment.class, note).commentActors()
            );
            var diff = shared.map("Note Hook", json.readTree(diffNote("feature/x").replace("b.human", "shared-bot")));
            assertEquals(
                List.of("smithy", "coordinator"),
                assertInstanceOf(WorkflowEvent.PrReviewComment.class, diff).commentActors()
            );
        }
    }

    @Test
    void aNoteByABotOnAnIssueIsStillIgnored() throws Exception {
        assertNull(mapper().map("Note Hook", json.readTree(issueNote("smithy-bot"))));
    }

    @Test
    void anUnhandledEventIsIgnoredWhateverItSaysAboutItsRepo() throws Exception {
        // The repo is only named in a log line, and hooks differ in whether
        // they carry one at all: none of these shapes may make the mapper fail.
        // The name is resolved before the debug level is checked, so this holds
        // at any log level.
        for (String payload : new String[] {
            """
            {"object_kind": "build", "project": {"path_with_namespace": "acme/app"}}""",
            """
            {"object_kind": "build", "project_name": "Acme / App"}""",
            """
            {"object_kind": "build", "project": null}""",
            "{}",
        }) {
            assertNull(mapper().map("Job Hook", json.readTree(payload)), payload);
        }
        assertNull(mapper().map("Job Hook", null));
    }

    @Test
    void addingTheReviewerBotAsksForAReview() throws Exception {
        var event = mapper().map(
            "Merge Request Hook",
            json.readTree(reviewersChanged("[]", "[{\"username\": \"architect-bot\"}]"))
        );
        assertEquals(41, assertInstanceOf(WorkflowEvent.ReviewRequested.class, event).prc().number());
    }

    @Test
    void openingAMergeRequestWithTheReviewerBotAsksForAReview() throws Exception {
        var event = mapper().map(
            "Merge Request Hook",
            json.readTree(openedWithReviewers("[{\"username\": \"architect-bot\"}]"))
        );
        var requested = assertInstanceOf(WorkflowEvent.ReviewRequested.class, event);
        assertEquals(41, requested.prc().number());
        assertEquals("feature/review-me", requested.prc().headBranch());
    }

    @Test
    void openingAMergeRequestWithoutTheReviewerBotIsNotAReviewRequest() throws Exception {
        assertNull(
            mapper().map("Merge Request Hook", json.readTree(openedWithReviewers("[{\"username\": \"b.human\"}]")))
        );
        assertNull(mapper().map("Merge Request Hook", json.readTree(openedWithReviewers("[]"))));
    }

    @Test
    void addingAnotherReviewerWhileTheBotStaysDoesNotReviewAgain() throws Exception {
        String withBot = "[{\"username\": \"architect-bot\"}]";
        String withBotAndHuman = "[{\"username\": \"architect-bot\"}, {\"username\": \"b.human\"}]";
        assertNull(mapper().map("Merge Request Hook", json.readTree(reviewersChanged(withBot, withBotAndHuman))));
    }

    @Test
    void removingTheReviewerBotWithdrawsTheReview() throws Exception {
        var event = mapper().map(
            "Merge Request Hook",
            json.readTree(reviewersChanged("[{\"username\": \"architect-bot\"}]", "[]"))
        );
        var withdrawn = assertInstanceOf(WorkflowEvent.ReviewWithdrawn.class, event);
        assertEquals("pr.review_withdrawn", withdrawn.name());
        assertEquals("FVS-1608", withdrawn.prc().headBranch());
    }

    private static String reviewersChanged(String previous, String current) {
        return """
        {
          "object_kind": "merge_request",
          "project": {"path_with_namespace": "acme/app", "git_http_url": "https://gitlab.invalid/acme/app.git",
                      "web_url": "https://gitlab.invalid/acme/app"},
          "object_attributes": {"iid": 41, "title": "A change", "description": "", "state": "opened",
                                "action": "update", "source_branch": "FVS-1608", "target_branch": "main"},
          "reviewers": %2$s,
          "changes": {"reviewers": {"previous": %1$s, "current": %2$s}}
        }
        """.formatted(previous, current);
    }

    private static String openedWithReviewers(String reviewers) {
        return """
        {
          "object_kind": "merge_request",
          "project": {"path_with_namespace": "acme/app", "git_http_url": "https://gitlab.invalid/acme/app.git",
                      "web_url": "https://gitlab.invalid/acme/app"},
          "object_attributes": {"iid": 41, "title": "A change", "description": "", "state": "opened",
                                "action": "open", "source_branch": "feature/review-me", "target_branch": "main"},
          "reviewers": %s
        }
        """.formatted(reviewers);
    }

    private static String diffNote(String sourceBranch) {
        return """
        {
          "object_kind": "note",
          "project": {"path_with_namespace": "acme/app", "git_http_url": "https://gitlab.invalid/acme/app.git",
                      "web_url": "https://gitlab.invalid/acme/app"},
          "user": {"username": "b.human"},
          "object_attributes": {"id": 99, "note": "please fix", "type": "DiffNote", "noteable_type": "MergeRequest",
                                "discussion_id": "d1",
                                "position": {"new_path": "src/App.java", "new_line": 12},
                                "author": {"username": "b.human"}},
          "merge_request": {"iid": 41, "title": "A change", "description": "", "state": "opened",
                            "source_branch": "%s", "target_branch": "main"}
        }
        """.formatted(sourceBranch);
    }

    private static String plainNote(String sourceBranch, String author) {
        return """
        {
          "object_kind": "note",
          "project": {"path_with_namespace": "acme/app", "git_http_url": "https://gitlab.invalid/acme/app.git",
                      "web_url": "https://gitlab.invalid/acme/app"},
          "user": {"username": "%2$s"},
          "object_attributes": {"id": 99, "note": "please fix", "noteable_type": "MergeRequest",
                                "discussion_id": "d1", "author": {"username": "%2$s"}},
          "merge_request": {"iid": 41, "title": "A change", "description": "", "state": "opened",
                            "source_branch": "%1$s", "target_branch": "main"}
        }
        """.formatted(sourceBranch, author);
    }

    private static String issueNote(String author) {
        return """
        {
          "object_kind": "note",
          "project": {"path_with_namespace": "acme/app", "git_http_url": "https://gitlab.invalid/acme/app.git",
                      "web_url": "https://gitlab.invalid/acme/app"},
          "user": {"username": "%s"},
          "object_attributes": {"id": 99, "note": "a comment", "noteable_type": "Issue",
                                "author": {"username": "%s"}},
          "issue": {"iid": 7, "title": "A thing", "description": "", "state": "opened"}
        }
        """.formatted(author, author);
    }

    private static String opened(String username) {
        return """
        {
          "object_kind": "issue",
          "project": {"path_with_namespace": "acme/app", "git_http_url": "https://gitlab.invalid/acme/app.git",
                      "web_url": "https://gitlab.invalid/acme/app"},
          "object_attributes": {"iid": 7, "title": "A thing", "description": "", "action": "open",
                                "state": "opened"},
          "assignees": [{"username": "%s"}]
        }
        """.formatted(username);
    }

    private GitLabEventMapper mapper() {
        return mapper(
            new BotConfig(
                new BotConfig.BotEntry("smithy-bot", "smithy@example.com"),
                new BotConfig.BotEntry("architect-bot", "architect@example.com"),
                new BotConfig.BotEntry("coordinator-bot", "coordinator@example.com")
            )
        );
    }

    private GitLabEventMapper mapper(BotConfig bots) {
        var gitlab = new VcsProviderConfig.GitLabProviderConfig(
            "https://gitlab.invalid",
            "https://gitlab.invalid",
            "secret",
            "smithy-token",
            "architect-token",
            "coordinator-token",
            "oauth2"
        );
        var vcs = new VcsProviderConfig("gitlab", null, null, gitlab, null, null);
        return new GitLabEventMapper(bots, vcs, WorkflowPolicyConfig.defaults(), null, "gitlab-main");
    }
}
