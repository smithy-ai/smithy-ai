package dev.smithyai.orchestrator.github;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.web.GitHubEventMapper;
import org.junit.jupiter.api.Test;

class GitHubEventMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void reviewCommentOnSmithyPrDoesNotDispatchDirectly() throws Exception {
        WorkflowEvent event = mapper().map("pull_request_review_comment", mapper.readTree(reviewCommentPayload()));

        assertNull(event);
    }

    @Test
    void submittedReviewDispatchesReviewSubmitted() throws Exception {
        WorkflowEvent event = mapper().map("pull_request_review", mapper.readTree(reviewSubmittedPayload()));

        var review = assertInstanceOf(WorkflowEvent.ReviewSubmitted.class, event);
        assertEquals(7, review.prc().number());
        assertEquals("smithy/12-add-github", review.prc().headBranch());
        assertEquals(99, review.reviewId());
        assertEquals("Please fix these comments.", review.reviewBody());
        assertEquals("reviewer", review.reviewer());
    }

    private GitHubEventMapper mapper() {
        var botConfig = new BotConfig(
            new BotConfig.BotEntry("smithy-bot", "smithy@example.com"),
            new BotConfig.BotEntry("architect-bot", "architect@example.com")
        );
        var github = new VcsProviderConfig.GitHubProviderConfig("", "", "secret", "smithy-token", "architect-token");
        var vcsConfig = new VcsProviderConfig("github", null, null, null, github);
        return new GitHubEventMapper(botConfig, vcsConfig, null);
    }

    private String reviewCommentPayload() {
        return """
        {
          "action": "created",
          "repository": {
            "full_name": "owner/repo",
            "clone_url": "https://github.com/owner/repo.git"
          },
          "pull_request": {
            "number": 7,
            "title": "Add GitHub",
            "body": "PR body",
            "merged": false,
            "head": { "ref": "smithy/12-add-github" },
            "base": { "ref": "main" }
          },
          "comment": {
            "body": "Fix this",
            "path": "src/App.java",
            "line": 42,
            "user": { "login": "reviewer" }
          }
        }
        """;
    }

    private String reviewSubmittedPayload() {
        return """
        {
          "action": "submitted",
          "repository": {
            "full_name": "owner/repo",
            "clone_url": "https://github.com/owner/repo.git"
          },
          "pull_request": {
            "number": 7,
            "title": "Add GitHub",
            "body": "PR body",
            "merged": false,
            "head": { "ref": "smithy/12-add-github" },
            "base": { "ref": "main" }
          },
          "review": {
            "id": 99,
            "body": "Please fix these comments.",
            "user": { "login": "reviewer" }
          }
        }
        """;
    }
}
