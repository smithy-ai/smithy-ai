package dev.smithyai.orchestrator.gitlab;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.config.WorkflowPolicyConfig;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.web.GitLabEventMapper;
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
        var bots = new BotConfig(
            new BotConfig.BotEntry("smithy-bot", "smithy@example.com"),
            new BotConfig.BotEntry("architect-bot", "architect@example.com"),
            new BotConfig.BotEntry("coordinator-bot", "coordinator@example.com")
        );
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
