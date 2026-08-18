package dev.smithyai.orchestrator.runtime.store;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.model.IssueContext;
import dev.smithyai.orchestrator.model.PrContext;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RunRecorderTest {

    private static final RepoInfo REPO = new RepoInfo("acme", "app", "https://git.invalid/acme/app");

    @TempDir
    Path tempDir;

    private RunStore store;
    private RunRecorder recorder;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("test.db") + "?foreign_keys=on");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        store = new RunStore(JdbcClient.create((DataSource) dataSource), new ObjectMapper());
        recorder = new RunRecorder(store);
    }

    private static WorkflowEvent.IssueAssigned issueAssigned(String issueRef) {
        var ctx = new IssueContext(REPO, issueRef, "A title", "A body", "main");
        return new WorkflowEvent.IssueAssigned(ctx, "https://git.invalid/acme/app");
    }

    @Test
    void opensARunAndIndexesItByContainerAndIssue() {
        String runId = recorder.openRun("smithy-development", "smithy.acme.app.7", "new", issueAssigned("7"));

        var byContainer = store.findByCorrelation(CorrelationKind.CONTAINER, "smithy.acme.app.7").orElseThrow();
        assertEquals(runId, byContainer.id());

        var byIssue = store.findByCorrelation(CorrelationKind.ISSUE, "acme/app#7").orElseThrow();
        assertEquals(runId, byIssue.id());

        assertEquals(RunStatus.RUNNING, store.find(runId).orElseThrow().status());
    }

    @Test
    void reopeningTheSameContainerReattachesInsteadOfForking() {
        String first = recorder.openRun("smithy-development", "smithy.acme.app.7", "new", issueAssigned("7"));
        // An orchestrator restart recovers the container and calls openRun again.
        String second = recorder.openRun("smithy-development", "smithy.acme.app.7", "new", null);

        assertEquals(first, second, "recovery must re-attach to the existing run, not start a second one");
        assertEquals(1, store.findRecent(10).size());
    }

    @Test
    void correlatesPrAndBranchSoLaterEventsFindTheRun() {
        String runId = recorder.openRun("smithy-development", "smithy.acme.app.7", "new", issueAssigned("7"));

        var prc = new PrContext(REPO, 42, "Draft: thing", "fixes #7", false, "smithy/7-thing", "main");
        recorder.correlateEvent(runId, new WorkflowEvent.PrFinalized(prc));

        assertEquals(runId, store.findByCorrelation(CorrelationKind.PR, "acme/app!42").orElseThrow().id());
        assertEquals(
            runId,
            store.findByCorrelation(CorrelationKind.BRANCH, "acme/app@smithy/7-thing").orElseThrow().id()
        );
    }

    @Test
    void closingARunDetachesTheContainerButKeepsTheHistory() {
        String runId = recorder.openRun("smithy-development", "smithy.acme.app.7", "new", issueAssigned("7"));
        recorder.recordState(runId, "build");
        recorder.recordEvent(runId, "plan_posted", null);

        recorder.closeRun(runId, RunStatus.COMPLETED);

        assertTrue(recorder.findByContainer("smithy.acme.app.7").isEmpty(), "container attachment is released");

        var run = store.find(runId).orElseThrow();
        assertEquals(RunStatus.COMPLETED, run.status());
        assertEquals("build", run.state(), "the run keeps the state it reached");
        assertEquals(1, store.findEvents(runId).size(), "history outlives the container");
    }

    @Test
    void recordingAgainstAnAbsentRunIsANoOp() {
        // The factory continues untracked if the store was unavailable at create
        // time; nothing downstream should throw because of it.
        assertDoesNotThrow(() -> {
            recorder.recordState(null, "build");
            recorder.recordEvent(null, "plan_posted", null);
            recorder.closeRun(null, RunStatus.COMPLETED);
            recorder.correlateEvent(null, null);
        });
    }
}
