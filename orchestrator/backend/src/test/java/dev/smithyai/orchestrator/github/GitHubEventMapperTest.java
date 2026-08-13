package dev.smithyai.orchestrator.github;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.config.WorkflowPolicyConfig;
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

    @Test
    void planApprovalLabelIsConfigurableRatherThanHardcoded() throws Exception {
        String payload = """
            {
              "action": "labeled",
              "label": {"name": "ship-it"},
              "sender": {"login": "alice"},
              "repository": {"full_name": "acme/app", "owner": {"login": "acme"}, "name": "app",
                             "html_url": "https://github.com/acme/app", "clone_url": "https://github.com/acme/app.git"},
              "issue": {"number": 7, "title": "A thing", "body": "", "state": "open"}
            }
            """;

        // The default label does not match this payload.
        assertNull(mapper().map("issues", mapper.readTree(payload)));

        // Configured to "ship-it", the same payload is an approval.
        var configured = new GitHubEventMapper(
            botConfig(),
            vcsConfig(),
            new WorkflowPolicyConfig("ship-it", null, null),
            null
        );
        var event = configured.map("issues", mapper.readTree(payload));
        assertInstanceOf(WorkflowEvent.PlanApproved.class, event);
        assertEquals("alice", ((WorkflowEvent.PlanApproved) event).approver());
    }

    private static BotConfig botConfig() {
        return new BotConfig(
            new BotConfig.BotEntry("smithy-bot", "smithy@example.com"),
            new BotConfig.BotEntry("architect-bot", "architect@example.com"),
            new BotConfig.BotEntry("coordinator-bot", "coordinator@example.com")
        );
    }

    private static VcsProviderConfig vcsConfig() {
        var github = new VcsProviderConfig.GitHubProviderConfig("", "", "secret", "smithy-token", "architect-token");
        return new VcsProviderConfig("github", null, null, null, github, null);
    }

    private GitHubEventMapper mapper() {
        var botConfig = new BotConfig(
            new BotConfig.BotEntry("smithy-bot", "smithy@example.com"),
            new BotConfig.BotEntry("architect-bot", "architect@example.com"),
            new BotConfig.BotEntry("coordinator-bot", "coordinator@example.com")
        );
        var github = new VcsProviderConfig.GitHubProviderConfig("", "", "secret", "smithy-token", "architect-token");
        var vcsConfig = new VcsProviderConfig("github", null, null, null, github, null);
        return new GitHubEventMapper(botConfig, vcsConfig, WorkflowPolicyConfig.defaults(), null);
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
