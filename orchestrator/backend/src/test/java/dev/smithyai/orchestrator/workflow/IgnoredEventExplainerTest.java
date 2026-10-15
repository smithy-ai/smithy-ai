package dev.smithyai.orchestrator.workflow;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.model.IssueContext;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.engine.RunEngine;
import dev.smithyai.orchestrator.runtime.store.CorrelationKind;
import dev.smithyai.orchestrator.runtime.store.Run;
import dev.smithyai.orchestrator.runtime.store.RunRecorder;
import dev.smithyai.orchestrator.runtime.store.RunStatus;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import dev.smithyai.orchestrator.service.vcs.IssueTrackers;
import dev.smithyai.orchestrator.testing.StubVcsClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Every silence in here was once a real investigation: a person assigned,
 * approved or asked, nothing reacted, and the reason had to be dug out of
 * DEBUG logs hours later. The explainer posts that reason up front.
 */
class IgnoredEventExplainerTest {

    private static final RepoInfo REPO = new RepoInfo(
        "acme",
        "platform",
        "https://git.invalid/acme/platform",
        "forgejo-main",
        "forgejo"
    );

    @TempDir
    Path tempDir;

    private RunStore store;
    private StubVcsClient tracker;
    private IgnoredEventExplainer explainer;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("runs.db") + "?foreign_keys=on");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        store = new RunStore(JdbcClient.create((DataSource) dataSource), new ObjectMapper());
        tracker = new StubVcsClient();
        explainer = new IgnoredEventExplainer(
            store,
            new IssueTrackers(Map.of("forgejo-main", tracker)),
            new dev.smithyai.orchestrator.config.DashboardConfig("https://smithy.example.com")
        );
    }

    private static WorkflowEvent comment(String body) {
        return new WorkflowEvent.IssueComment(
            new IssueContext(REPO, "ECD-9", "Add search", "body", "main", "smithy"),
            body
        );
    }

    private static WorkflowEvent assigned() {
        return new WorkflowEvent.IssueAssigned(
            new IssueContext(REPO, "ECD-9", "Add search", "body", "main", "coordinator"),
            null
        );
    }

    private Run ownedRun(RunStatus status, String state) {
        var run = store.create("feature-coordinator", "1", state, null);
        store.updateState(run.id(), state);
        store.updateStatus(run.id(), status);
        store.correlate(CorrelationKind.ISSUE, RunRecorder.issueRef("acme", "platform", "ECD-9"), run.id());
        return run;
    }

    private static List<RunEngine.Outcome> nothingHandled() {
        return List.of(new RunEngine.Outcome("feature-coordinator", null, null, null, false));
    }

    @Test
    void aCommentOnACompletedRunExplainsHowToStartOver() {
        ownedRun(RunStatus.COMPLETED, "done");

        explainer.explainIfIgnored(comment("what is the status?"), nothingHandled());

        assertEquals(1, tracker.issueComments.size(), tracker.issueComments.toString());
        var reply = tracker.issueComments.getFirst();
        assertTrue(reply.contains("completed"), reply);
        assertTrue(reply.contains("assign me again"), reply);
    }

    @Test
    void anAssignmentNoWorkflowClaimsIsExplained() {
        explainer.explainIfIgnored(assigned(), List.of());

        assertEquals(1, tracker.issueComments.size(), tracker.issueComments.toString());
        assertTrue(tracker.issueComments.getFirst().contains("no workflow"), tracker.issueComments.getFirst());
    }

    @Test
    void aGestureAtAStageWithNoEarForItNamesTheStage() {
        ownedRun(RunStatus.WAITING, "executing");

        explainer.explainIfIgnored(comment("please hurry"), nothingHandled());

        assertEquals(1, tracker.issueComments.size(), tracker.issueComments.toString());
        assertTrue(tracker.issueComments.getFirst().contains("state 'executing'"), tracker.issueComments.getFirst());
    }

    /**
     * A comment nobody acts on is a dead end unless the reply says where the
     * person can be heard: the run's session, when there is one to open.
     */
    @Test
    void anUnheardCommentOnARunWithASessionPointsAtTheSession() {
        var run = ownedRun(RunStatus.WAITING, "awaiting_approval");
        store.attachEnvironment(run.id(), RunRecorder.CONTAINER, "gm.coordinator.ECD-9", Map.of());

        explainer.explainIfIgnored(comment("can we drop the frontend part?"), nothingHandled());

        var reply = tracker.issueComments.getFirst();
        assertTrue(reply.contains("https://smithy.example.com/session/gm.coordinator.ECD-9"), reply);
        assertTrue(reply.contains("open the session"), reply);
    }

    @Test
    void noSessionHintWithoutAContainerOrADashboardAddress() {
        ownedRun(RunStatus.WAITING, "executing");
        explainer.explainIfIgnored(comment("status?"), nothingHandled());
        assertFalse(tracker.issueComments.getFirst().contains("session"), tracker.issueComments.getFirst());

        var silentDashboard = new IgnoredEventExplainer(
            store,
            new IssueTrackers(Map.of("forgejo-main", tracker)),
            dev.smithyai.orchestrator.config.DashboardConfig.unconfigured()
        );
        var run = store
            .findByCorrelation(CorrelationKind.ISSUE, RunRecorder.issueRef("acme", "platform", "ECD-9"))
            .orElseThrow();
        store.attachEnvironment(run.id(), RunRecorder.CONTAINER, "box", Map.of());
        silentDashboard.explainIfIgnored(comment("status again?"), nothingHandled());
        assertFalse(tracker.issueComments.getLast().contains("session"), tracker.issueComments.getLast());
    }

    @Test
    void aRedeliveredAssignmentOnAWorkingRunStaysSilent() {
        // The run exists because of that assignment; a tracker redelivering the
        // webhook mid-planning is not a person who needs an explanation.
        ownedRun(RunStatus.RUNNING, "awaiting_approval");

        explainer.explainIfIgnored(assigned(), nothingHandled());

        assertTrue(tracker.issueComments.isEmpty(), "the acknowledgement already said 'on it'");
    }

    @Test
    void aHandledEventNeedsNoExplanation() {
        ownedRun(RunStatus.COMPLETED, "done");

        explainer.explainIfIgnored(
            comment("looks good"),
            List.of(new RunEngine.Outcome("feature-coordinator", "run", "a", "b", true))
        );

        assertTrue(tracker.issueComments.isEmpty());
    }

    @Test
    void aCommentOnAnIssueNoRunEverOwnedIsNoneOfTheBotsBusiness() {
        explainer.explainIfIgnored(comment("hi team, thoughts?"), List.of());

        assertTrue(tracker.issueComments.isEmpty(), "a human conversation is not answered");
    }

    @Test
    void machineTrafficIsNeverExplained() {
        ownedRun(RunStatus.COMPLETED, "done");

        explainer.explainIfIgnored(new WorkflowEvent.HumanPush(REPO, "some-branch"), List.of());

        assertTrue(tracker.issueComments.isEmpty(), "pushes go unhandled all the time, legitimately");
    }
}
