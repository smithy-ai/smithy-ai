package dev.smithyai.orchestrator.runtime.actions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.IssueTrackers;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.VcsClients;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActorAssignmentActionsTest {

    private static final String CONNECTOR = "forgejo-main";
    private static final ActionContext CONTEXT = new ActionContext(null, null, Map.of(), Map.of());

    private VcsClient vcs;
    private IssueTrackerClient issues;
    private VcsClients vcsClients;
    private IssueTrackers issueTrackers;

    @BeforeEach
    void setUp() {
        vcs = mock(VcsClient.class);
        issues = mock(IssueTrackerClient.class);
        vcsClients = new VcsClients(
            Map.of("smithy", Map.of(CONNECTOR, vcs)),
            "smithy",
            CONNECTOR,
            (connector, actor) -> actor + "-bot",
            connector -> "https://git.example"
        );
        issueTrackers = new IssueTrackers(
            Map.of("smithy", Map.of(CONNECTOR, issues)),
            "smithy",
            CONNECTOR,
            (connector, actor) -> actor + "-bot"
        );
    }

    @Test
    void assignmentChecksResolveTheLogicalActorToAProviderUsername() {
        when(vcs.isAssigned("acme", "api", 7, "smithy-bot")).thenReturn(true);

        var output = new ReviewActions()
            .prIsAssignedAction(vcsClients)
            .execute(CONTEXT, Map.of("owner", "acme", "repo", "api", "number", 7, "assignedActor", "smithy"));

        assertEquals(true, output.get("assigned"));
        verify(vcs).isAssigned("acme", "api", 7, "smithy-bot");
    }

    @Test
    void assignmentActionsResolveActorsBeforeCallingProviders() {
        new IssueActions()
            .issueAssignAction(issueTrackers)
            .execute(CONTEXT, Map.of("owner", "acme", "repo", "api", "issue", "12", "actors", List.of("smithy")));
        new ReviewActions()
            .prSetAssigneesAction(vcsClients)
            .execute(CONTEXT, Map.of("owner", "acme", "repo", "api", "number", 7, "actors", List.of("smithy")));

        verify(issues).setIssueAssignees("acme", "api", "12", List.of("smithy-bot"));
        verify(vcs).setPrAssignees("acme", "api", 7, List.of("smithy-bot"));
    }

    @Test
    void assignmentActionsRejectMissingActorsInsteadOfClearingAssignments() {
        var issueAction = new IssueActions().issueAssignAction(issueTrackers);
        var prAction = new ReviewActions().prSetAssigneesAction(vcsClients);

        var issueError = assertThrows(IllegalArgumentException.class, () ->
            issueAction.execute(CONTEXT, Map.of("owner", "acme", "repo", "api", "issue", "12"))
        );
        var prError = assertThrows(IllegalArgumentException.class, () ->
            prAction.execute(CONTEXT, Map.of("owner", "acme", "repo", "api", "number", 7))
        );

        assertEquals("issue.assign requires 'actors'", issueError.getMessage());
        assertEquals("pr.setAssignees requires 'actors'", prError.getMessage());
        verifyNoInteractions(issues, vcs);
    }

    @Test
    void reviewRequestsExcludeTheResolvedActorUsername() {
        new PullRequestActions()
            .prRequestReviewAction(vcsClients)
            .execute(
                CONTEXT,
                Map.of(
                    "owner",
                    "acme",
                    "repo",
                    "api",
                    "number",
                    7,
                    "reviewers",
                    List.of("smithy-bot", "alice"),
                    "notFromActor",
                    "smithy"
                )
            );

        verify(vcs).requestReview("acme", "api", 7, List.of("alice"));
    }

    @Test
    void missingWorkflowActorDoesNotBorrowTheDefaultIdentity() {
        var architectContext = new ActionContext(null, null, Map.of(), Map.of(), "architect");

        var vcsError = assertThrows(IllegalArgumentException.class, () ->
            new ReviewActions()
                .prIsAssignedAction(vcsClients)
                .execute(
                    architectContext,
                    Map.of("owner", "acme", "repo", "api", "number", 7, "assignedActor", "smithy")
                )
        );
        var issueError = assertThrows(IllegalArgumentException.class, () ->
            new IssueActions()
                .issueAssignAction(issueTrackers)
                .execute(
                    architectContext,
                    Map.of("owner", "acme", "repo", "api", "issue", "12", "actors", List.of("smithy"))
                )
        );

        assertTrue(vcsError.getMessage().contains("actor 'architect'"));
        assertTrue(issueError.getMessage().contains("actor 'architect'"));
        verifyNoInteractions(issues, vcs);
    }
}
