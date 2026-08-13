package dev.smithyai.orchestrator.jira;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.web.JiraEventMapper;
import org.junit.jupiter.api.Test;

class JiraEventMapperTest {

    private static final String BOT = "bot-account-123";
    private static final String ARCHITECT = "architect-account-456";
    private static final String COORDINATOR = "coordinator-account-789";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final IssueTrackerClient issueTracker = mock(IssueTrackerClient.class);

    private JiraEventMapper mapper() {
        var jira = new VcsProviderConfig.JiraProviderConfig(
            "https://example.atlassian.net",
            "bot@example.com",
            "token",
            BOT,
            ARCHITECT,
            COORDINATOR,
            "secret",
            "customfield_10010",
            null,
            "In Development",
            null
        );
        var vcsConfig = new VcsProviderConfig("gitlab", "jira", null, null, null, jira);
        VcsClient vcs = mock(VcsClient.class);
        when(vcs.cloneUrl(any(), any())).thenAnswer(
            inv -> "https://gitlab.example.com/" + inv.getArgument(0) + "/" + inv.getArgument(1) + ".git"
        );
        when(vcs.baseUrl()).thenReturn("https://gitlab.example.com");
        return new JiraEventMapper(vcsConfig, vcs, issueTracker);
    }

    private String issueJson(String assigneeId, String repoFieldValue) {
        return """
        {
          "key": "ECD-4309",
          "fields": {
            "summary": "Add login",
            "description": "Details",
            "assignee": %s,
            "labels": [],
            "customfield_10010": %s
          }
        }
        """.formatted(
                assigneeId == null ? "null" : "{\"accountId\": \"" + assigneeId + "\"}",
                repoFieldValue == null ? "null" : "\"" + repoFieldValue + "\""
            );
    }

    @Test
    void assignmentToBotProducesIssueAssigned() throws Exception {
        String payload = """
            { "webhookEvent": "jira:issue_updated",
              "issue": %s,
              "changelog": { "items": [ { "field": "assignee", "from": null, "to": "%s" } ] } }
            """.formatted(issueJson(BOT, "gerimedica/ecd-care@develop"), BOT);

        var event = mapper().map(JSON.readTree(payload));
        var assigned = assertInstanceOf(WorkflowEvent.IssueAssigned.class, event);
        assertEquals("ECD-4309", assigned.ctx().issueRef());
        assertEquals("gerimedica", assigned.ctx().info().owner());
        assertEquals("ecd-care", assigned.ctx().info().repo());
        assertEquals("develop", assigned.ctx().baseBranch());
    }

    @Test
    void aStoryHandedToTheCoordinatorSaysSo() throws Exception {
        // Jira accounts are the actors here: without one of its own, a
        // coordinator could not be handed a story at all.
        String payload = """
            { "webhookEvent": "jira:issue_updated",
              "issue": %s,
              "changelog": { "items": [ { "field": "assignee", "from": null, "to": "%s" } ] } }
            """.formatted(issueJson(COORDINATOR, "gerimedica/ecd-care"), COORDINATOR);

        var assigned = assertInstanceOf(WorkflowEvent.IssueAssigned.class, mapper().map(JSON.readTree(payload)));
        assertEquals("coordinator", assigned.ctx().assignee());
        assertEquals("jira", assigned.ctx().info().source());
    }

    @Test
    void assignmentToTheAgentSaysTheAgent() throws Exception {
        String payload = """
            { "webhookEvent": "jira:issue_updated",
              "issue": %s,
              "changelog": { "items": [ { "field": "assignee", "from": null, "to": "%s" } ] } }
            """.formatted(issueJson(BOT, "gerimedica/ecd-care"), BOT);

        var assigned = assertInstanceOf(WorkflowEvent.IssueAssigned.class, mapper().map(JSON.readTree(payload)));
        assertEquals("smithy", assigned.ctx().assignee());
    }

    @Test
    void assignmentWithoutRepoFieldIsIgnoredAndCommented() throws Exception {
        String payload = """
            { "webhookEvent": "jira:issue_updated",
              "issue": %s,
              "changelog": { "items": [ { "field": "assignee", "from": null, "to": "%s" } ] } }
            """.formatted(issueJson(BOT, null), BOT);

        assertNull(mapper().map(JSON.readTree(payload)));
        verify(issueTracker).createIssueComment(any(), any(), eq("ECD-4309"), contains("repository field"));
    }

    @Test
    void unassignmentProducesIssueUnassigned() throws Exception {
        String payload = """
            { "webhookEvent": "jira:issue_updated",
              "issue": %s,
              "changelog": { "items": [ { "field": "assignee", "from": "%s", "to": "someone-else" } ] } }
            """.formatted(issueJson("someone-else", "gerimedica/ecd-care"), BOT);

        assertInstanceOf(WorkflowEvent.IssueUnassigned.class, mapper().map(JSON.readTree(payload)));
    }

    @Test
    void planApprovedLabelProducesPlanApproved() throws Exception {
        String payload = """
            { "webhookEvent": "jira:issue_updated",
              "issue": %s,
              "changelog": { "items": [ { "field": "labels", "fromString": "", "toString": "plan-approved" } ] } }
            """.formatted(issueJson(BOT, "gerimedica/ecd-care"));

        assertInstanceOf(WorkflowEvent.PlanApproved.class, mapper().map(JSON.readTree(payload)));
    }

    @Test
    void statusTransitionProducesPlanApproved() throws Exception {
        String payload = """
            { "webhookEvent": "jira:issue_updated",
              "issue": %s,
              "changelog": { "items": [ { "field": "status", "fromString": "To Do", "toString": "In Development" } ] } }
            """.formatted(issueJson(BOT, "gerimedica/ecd-care"));

        assertInstanceOf(WorkflowEvent.PlanApproved.class, mapper().map(JSON.readTree(payload)));
    }

    @Test
    void humanCommentProducesIssueComment() throws Exception {
        String payload = """
            { "webhookEvent": "comment_created",
              "issue": %s,
              "comment": { "id": 5, "body": "please adjust", "author": { "accountId": "human-1" } } }
            """.formatted(issueJson(BOT, "gerimedica/ecd-care"));

        var event = mapper().map(JSON.readTree(payload));
        var comment = assertInstanceOf(WorkflowEvent.IssueComment.class, event);
        assertEquals("please adjust", comment.commentBody());
    }

    @Test
    void botCommentIsIgnored() throws Exception {
        String payload = """
            { "webhookEvent": "comment_created",
              "issue": %s,
              "comment": { "id": 5, "body": "plan posted", "author": { "accountId": "%s" } } }
            """.formatted(issueJson(BOT, "gerimedica/ecd-care"), BOT);

        assertNull(mapper().map(JSON.readTree(payload)));
    }
}
