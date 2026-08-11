package dev.smithyai.orchestrator.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.model.IssueContext;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.actions.*;
import dev.smithyai.orchestrator.runtime.store.Run;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import java.nio.file.Path;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Waiting for a human, and a child reaching its parent directly.
 *
 * <p>Both of these used to travel through the VCS: approval was a label the flow
 * re-derived on every event, and a child announced itself by posting a comment
 * its parent string-matched back out of the bot's own output.
 */
class GateAndSignalTest {

    private static final RepoInfo REPO = new RepoInfo("acme", "platform", "https://git.invalid/acme/platform");

    @TempDir
    Path tempDir;

    private RunStore store;
    private GateAwaitAction gate;
    private SignalEmitAction signal;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("runs.db") + "?foreign_keys=on");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        store = new RunStore(JdbcClient.create((DataSource) dataSource), new ObjectMapper());
        gate = new GateAwaitAction(store);
        signal = new SignalEmitAction(store);
    }

    private static WorkflowEvent event() {
        return new WorkflowEvent.PlanApproved(new IssueContext(REPO, "ECD-1", "A feature", "body", "main"), "alice");
    }

    private ActionContext contextFor(Run run) {
        return new ActionContext(run, event(), Map.of(), run.vars());
    }

    @Test
    void aGateHoldsUntilSomethingReleasesIt() {
        var run = store.create("feature-coordinator", null, "planning", null);
        var context = contextFor(run);

        assertEquals(false, gate.execute(context, Map.of("key", "plan-approval")).get("satisfied"));
        assertEquals(1, store.findPendingWaits(run.id()).size());

        // The human approves — from the dashboard, or via an approval label.
        assertEquals(1, store.satisfyWait(run.id(), "plan-approval"));

        assertEquals(true, gate.execute(context, Map.of("key", "plan-approval")).get("satisfied"));
        assertTrue(store.findPendingWaits(run.id()).isEmpty());
    }

    @Test
    void rearmingAGateDoesNotStackUpWaits() {
        var run = store.create("feature-coordinator", null, "planning", null);
        var context = contextFor(run);

        // Every inbound event re-runs the transition and re-evaluates the gate.
        gate.execute(context, Map.of("key", "plan-approval"));
        gate.execute(context, Map.of("key", "plan-approval"));
        gate.execute(context, Map.of("key", "plan-approval"));

        assertEquals(1, store.findPendingWaits(run.id()).size());
    }

    @Test
    void twoGatesOnOneRunAreIndependent() {
        var run = store.create("feature-coordinator", null, "planning", null);
        var context = contextFor(run);

        gate.execute(context, Map.of("key", "plan-approval"));
        gate.execute(context, Map.of("key", "wave-2-approval"));
        store.satisfyWait(run.id(), "plan-approval");

        assertEquals(true, gate.execute(context, Map.of("key", "plan-approval")).get("satisfied"));
        assertEquals(false, gate.execute(context, Map.of("key", "wave-2-approval")).get("satisfied"));
    }

    @Test
    void aChildReachesItsParentWithoutGoingThroughTheVcs() {
        var parent = store.create("feature-coordinator", null, "executing", null);
        var child = store.create("smithy-development", null, "refine", parent.id());
        gate.execute(contextFor(parent), Map.of("key", "child-planned"));

        var result = signal.execute(contextFor(child), Map.of("signal", "child-planned", "prNumber", 41));

        assertEquals(parent.id(), result.get("to"), "parent is the default target");
        assertEquals(1, result.get("released"));
        assertEquals(true, gate.execute(contextFor(parent), Map.of("key", "child-planned")).get("satisfied"));
    }

    @Test
    void aSignalLandsInTheTargetsHistoryWithItsPayload() {
        var parent = store.create("feature-coordinator", null, "executing", null);
        var child = store.create("smithy-development", null, "refine", parent.id());

        signal.execute(contextFor(child), Map.of("signal", "child-planned", "prNumber", 41));

        var events = store.findEvents(parent.id());
        assertEquals(1, events.size());
        assertEquals("signal:child-planned", events.getFirst().type());
        assertEquals(41, events.getFirst().payload().get("prNumber"));
        assertEquals(child.id(), events.getFirst().payload().get("from"), "and says who sent it");
    }

    @Test
    void signallingBeforeTheGateIsArmedStillReleasesIt() {
        var parent = store.create("feature-coordinator", null, "executing", null);
        var child = store.create("smithy-development", null, "refine", parent.id());

        // A coordinator arms its join in the same transition that spawns the
        // children it joins on, so a fast child can report back first. Dropping
        // that release would hang the coordinator forever.
        signal.execute(contextFor(child), Map.of("signal", "child-planned"));
        var armed = gate.execute(contextFor(parent), Map.of("key", "child-planned"));

        assertEquals(true, armed.get("satisfied"));
        assertTrue(store.findPendingWaits(parent.id()).isEmpty());
    }

    @Test
    void aSignalWithNowhereToGoFailsLoudly() {
        var orphan = store.create("smithy-development", null, "refine", null);
        var context = contextFor(orphan);

        var noParent = assertThrows(
            IllegalArgumentException.class,
            () -> signal.execute(context, Map.of("signal", "done"))
        );
        assertTrue(noParent.getMessage().contains("no parent"));

        assertThrows(
            IllegalArgumentException.class,
            () -> signal.execute(context, Map.of("signal", "done", "to", "run-that-never-existed"))
        );
    }
}
