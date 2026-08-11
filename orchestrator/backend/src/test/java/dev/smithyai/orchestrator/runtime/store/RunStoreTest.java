package dev.smithyai.orchestrator.runtime.store;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
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
 * Exercises the store against a real SQLite file, migrated by Flyway — the same
 * path production takes, so an unsupported-database or bad-SQL problem shows up
 * here rather than at container start.
 */
class RunStoreTest {

    @TempDir
    Path tempDir;

    private RunStore store;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = tempDir.resolve("test.db");
        Files.deleteIfExists(dbFile);

        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + dbFile + "?foreign_keys=on");
        dataSource.setDriverClassName("org.sqlite.JDBC");

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        store = new RunStore(JdbcClient.create((DataSource) dataSource), new ObjectMapper());
    }

    @Test
    void createsAndReadsBackARun() {
        var run = store.create("smithy-development", "v1", "refine", null);

        assertNotNull(run.id());
        assertEquals(RunStatus.PENDING, run.status());
        assertEquals("refine", run.state());
        // A run with no parent is its own root.
        assertEquals(run.id(), run.rootRunId());

        var loaded = store.find(run.id()).orElseThrow();
        assertEquals(run.id(), loaded.id());
        assertEquals("smithy-development", loaded.workflowName());
    }

    @Test
    void survivesStateAndStatusTransitions() {
        var run = store.create("smithy-development", "v1", "refine", null);

        store.updateState(run.id(), "build");
        store.updateStatus(run.id(), RunStatus.RUNNING);

        var mid = store.find(run.id()).orElseThrow();
        assertEquals("build", mid.state());
        assertEquals(RunStatus.RUNNING, mid.status());
        assertNull(mid.terminalAt(), "a non-terminal status must not stamp terminal_at");

        store.updateStatus(run.id(), RunStatus.COMPLETED);
        var done = store.find(run.id()).orElseThrow();
        assertTrue(done.isTerminal());
        assertNotNull(done.terminalAt());
    }

    @Test
    void storesVarsAsJson() {
        var run = store.create("smithy-development", "v1", "refine", null);
        store.updateVars(run.id(), Map.of("prNumber", 42, "branch", "smithy/7-thing"));

        var loaded = store.find(run.id()).orElseThrow();
        assertEquals(42, loaded.vars().get("prNumber"));
        assertEquals("smithy/7-thing", loaded.vars().get("branch"));
    }

    @Test
    void correlationFindsTheOwningRun() {
        var run = store.create("smithy-development", "v1", "refine", null);
        store.correlate(CorrelationKind.ISSUE, "acme/app#7", run.id());

        var found = store.findByCorrelation(CorrelationKind.ISSUE, "acme/app#7").orElseThrow();
        assertEquals(run.id(), found.id());

        assertTrue(store.findByCorrelation(CorrelationKind.ISSUE, "acme/app#8").isEmpty());
    }

    @Test
    void correlationCanBeRepointedToANewerRun() {
        var first = store.create("smithy-development", "v1", "refine", null);
        var second = store.create("smithy-development", "v1", "refine", null);

        store.correlate(CorrelationKind.BRANCH, "acme/app@smithy/7-thing", first.id());
        store.correlate(CorrelationKind.BRANCH, "acme/app@smithy/7-thing", second.id());

        var found = store.findByCorrelation(CorrelationKind.BRANCH, "acme/app@smithy/7-thing").orElseThrow();
        assertEquals(second.id(), found.id());
    }

    @Test
    void childRunsShareTheirParentsRoot() {
        var parent = store.create("feature-coordinator", "v1", "planning", null);
        var child = store.create("smithy-development", "v1", "refine", parent.id());
        var grandchild = store.create("smithy-development", "v1", "refine", child.id());

        assertEquals(parent.id(), child.rootRunId());
        assertEquals(parent.id(), grandchild.rootRunId(), "root propagates through the whole tree");

        var children = store.findChildren(parent.id());
        assertEquals(1, children.size());
        assertEquals(child.id(), children.getFirst().id());
    }

    @Test
    void eventsAreAppendedInOrder() {
        var run = store.create("smithy-development", "v1", "refine", null);

        assertEquals(1, store.appendEvent(run.id(), "plan_posted", null));
        assertEquals(2, store.appendEvent(run.id(), "pr_opened", Map.of("pr", 12)));

        List<RunEvent> events = store.findEvents(run.id());
        assertEquals(2, events.size());
        assertEquals("plan_posted", events.get(0).type());
        assertEquals(12, events.get(1).payload().get("pr"));
    }

    @Test
    void countsEventsByTypeAcrossRuns() {
        var a = store.create("smithy-development", "v1", "refine", null);
        var b = store.create("smithy-development", "v1", "refine", null);
        store.appendEvent(a.id(), "ci_failure", null);
        store.appendEvent(b.id(), "ci_failure", null);
        store.appendEvent(b.id(), "plan_posted", null);

        var counts = store.countEventsByType();
        assertEquals(2L, counts.get("ci_failure"));
        assertEquals(1L, counts.get("plan_posted"));
    }

    @Test
    void environmentsResolveBackToTheirRun() {
        var run = store.create("smithy-development", "v1", "refine", null);
        store.attachEnvironment(run.id(), "container", "smithy.acme.app.7", Map.of("sessionId", "abc"));

        var found = store.findByEnvironment("container", "smithy.acme.app.7").orElseThrow();
        assertEquals(run.id(), found.id());
        assertEquals(List.of("smithy.acme.app.7"), store.findEnvironmentNames(run.id(), "container"));

        store.detachEnvironment("container", "smithy.acme.app.7");
        assertTrue(store.findByEnvironment("container", "smithy.acme.app.7").isEmpty());
    }

    @Test
    void theRunOutlivesItsContainer() {
        var run = store.create("smithy-development", "v1", "build", null);
        store.attachEnvironment(run.id(), "container", "smithy.acme.app.7", null);
        store.appendEvent(run.id(), "plan_posted", null);

        // The container is removed — under the old design this erased the run.
        store.detachEnvironment("container", "smithy.acme.app.7");

        var survivor = store.find(run.id()).orElseThrow();
        assertEquals("build", survivor.state());
        assertEquals(1, store.findEvents(run.id()).size());
    }

    @Test
    void activeRunsExcludeTerminalOnes() {
        var running = store.create("smithy-development", "v1", "build", null);
        var finished = store.create("smithy-development", "v1", "done", null);
        store.updateStatus(running.id(), RunStatus.RUNNING);
        store.updateStatus(finished.id(), RunStatus.COMPLETED);

        var active = store.findActive();
        assertEquals(1, active.size());
        assertEquals(running.id(), active.getFirst().id());
    }
}
