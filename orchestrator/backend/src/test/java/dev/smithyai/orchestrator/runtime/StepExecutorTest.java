package dev.smithyai.orchestrator.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.model.IssueContext;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.actions.ActionContext;
import dev.smithyai.orchestrator.runtime.actions.ActionRegistry;
import dev.smithyai.orchestrator.runtime.actions.WorkflowAction;
import dev.smithyai.orchestrator.runtime.definition.WorkflowStepDefinition;
import dev.smithyai.orchestrator.runtime.engine.ExpressionRenderer;
import dev.smithyai.orchestrator.runtime.engine.StepExecutor;
import dev.smithyai.orchestrator.runtime.store.Run;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The resume contract. A transition can be interrupted mid-flight — a 30-minute
 * agent turn inside one is normal — so re-running it must not repeat side
 * effects that already happened.
 */
class StepExecutorTest {

    private static final RepoInfo REPO = new RepoInfo("acme", "app", "https://git.invalid/acme/app");
    private static final IssueContext ISSUE = new IssueContext(REPO, "7", "Add a thing", "body", "main");

    @TempDir
    Path tempDir;

    private RunStore store;
    private RecordingAction sideEffect;
    private AtomicInteger idempotentCalls;
    private StepExecutor executor;
    private Run run;

    /** Stands in for anything with a side effect, e.g. opening a pull request. */
    private static class RecordingAction implements WorkflowAction {

        final List<String> calls = new ArrayList<>();
        boolean failNext = false;

        @Override
        public String type() {
            return "pr.create";
        }

        @Override
        public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("provider unavailable");
            }
            calls.add(String.valueOf(input.get("title")));
            return Map.of("number", 100 + calls.size());
        }
    }

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("runs.db") + "?foreign_keys=on");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        store = new RunStore(JdbcClient.create((DataSource) dataSource), new ObjectMapper());

        sideEffect = new RecordingAction();
        idempotentCalls = new AtomicInteger();

        WorkflowAction touch = new WorkflowAction() {
            @Override
            public String type() {
                return "state.touch";
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                idempotentCalls.incrementAndGet();
                return Map.of("ok", true);
            }
        };

        executor = new StepExecutor(new ActionRegistry(List.of(sideEffect, touch)), new ExpressionRenderer(), store);
        run = store.create("smithy-development", "v1", "refine", null);
    }

    private static WorkflowEvent event() {
        return new WorkflowEvent.IssueAssigned(
            new IssueContext(REPO, "7", "Add a thing", "body", "main"),
            "https://git.invalid/acme/app"
        );
    }

    private static WorkflowStepDefinition step(String uses, String id, Map<String, Object> with) {
        return new WorkflowStepDefinition(uses, id, null, with, null);
    }

    @Test
    void runsStepsAndExposesTheirOutputs() {
        var outputs = executor.execute(
            run,
            event(),
            "refine:issue.assigned",
            List.of(step("pr.create", "pr", Map.of("title", "Draft: {{ event.issueTitle }}")))
        );

        assertEquals(List.of("Draft: Add a thing"), sideEffect.calls);
        assertEquals(101, outputs.get("pr").get("number"));
    }

    @Test
    void aStepsOutputIsVisibleToLaterSteps() {
        var outputs = executor.execute(
            run,
            event(),
            "refine:issue.assigned",
            List.of(
                step("pr.create", "pr", Map.of("title", "First")),
                step("pr.create", "second", Map.of("title", "PR was {{ steps.pr.number }}"))
            )
        );

        assertEquals(List.of("First", "PR was 101"), sideEffect.calls);
        assertEquals(102, outputs.get("second").get("number"));
    }

    @Test
    void resumingATransitionDoesNotRepeatACompletedSideEffect() {
        var steps = List.of(
            step("pr.create", "pr", Map.of("title", "Draft")),
            step("pr.create", "announce", Map.of("title", "Announce"))
        );

        // First attempt gets through the first step, then the process dies.
        executor.execute(run, event(), "refine:issue.assigned", List.of(steps.getFirst()));
        assertEquals(List.of("Draft"), sideEffect.calls);

        // The orchestrator restarts and replays the whole transition.
        executor.execute(run, event(), "refine:issue.assigned", steps);

        assertEquals(
            List.of("Draft", "Announce"),
            sideEffect.calls,
            "the completed step is skipped; only the outstanding one runs"
        );
    }

    @Test
    void aSkippedStepStillExposesItsRecordedOutput() {
        executor.execute(
            run,
            event(),
            "refine:issue.assigned",
            List.of(step("pr.create", "pr", Map.of("title", "Draft")))
        );

        // Replay with a later step that references the skipped step's output.
        var outputs = executor.execute(
            run,
            event(),
            "refine:issue.assigned",
            List.of(
                step("pr.create", "pr", Map.of("title", "Draft")),
                step("pr.create", "followup", Map.of("title", "Re {{ steps.pr.number }}"))
            )
        );

        assertEquals(List.of("Draft", "Re 101"), sideEffect.calls);
        assertEquals(101, outputs.get("pr").get("number"), "the prior output is reused, not recomputed");
    }

    @Test
    void anIdempotentStepIsReRunRatherThanSkipped() {
        var steps = List.of(step("state.touch", "touch", Map.of()));

        executor.execute(run, event(), "refine:issue.assigned", steps);
        executor.execute(run, event(), "refine:issue.assigned", steps);

        assertEquals(2, idempotentCalls.get(), "declaring idempotent means repeating is safe and keeps output fresh");
    }

    @Test
    void aFailedStepIsRecordedAndRethrown() {
        sideEffect.failNext = true;

        var error = assertThrows(IllegalStateException.class, () ->
            executor.execute(
                run,
                event(),
                "refine:issue.assigned",
                List.of(step("pr.create", "pr", Map.of("title", "Draft")))
            )
        );
        assertEquals("provider unavailable", error.getMessage());

        // Not recorded as completed, so a resume retries it.
        assertTrue(store.findStepOutput(run.id(), "refine:issue.assigned", "pr").isEmpty());
    }

    @Test
    void aFalseConditionSkipsTheStepEntirely() {
        var conditional = new WorkflowStepDefinition(
            "pr.create",
            "pr",
            "{{ vars.enabled }}",
            Map.of("title", "Draft"),
            null
        );

        executor.execute(run, event(), "refine:issue.assigned", List.of(conditional));

        assertEquals(List.of(), sideEffect.calls, "vars.enabled is unset, so the step does not run");
    }

    @Test
    void anUnknownActionFailsLoudly() {
        var error = assertThrows(IllegalStateException.class, () ->
            executor.execute(run, event(), "refine:issue.assigned", List.of(step("does.not.exist", "x", Map.of())))
        );
        assertTrue(error.getMessage().contains("does.not.exist"), error.getMessage());
    }

    @Test
    void twoDistinctEventsOfTheSameNameEachGetTheirOwnTransition() {
        var first = new WorkflowEvent.IssueComment(ISSUE, "please rename the field");
        var second = new WorkflowEvent.IssueComment(ISSUE, "and add a test for it");

        // The same state and the same event name — only the comment differs.
        assertNotEquals(
            StepExecutor.transitionId("refine", first),
            StepExecutor.transitionId("refine", second),
            "a second comment is new work, not a replay of the first"
        );
    }

    @Test
    void aRedeliveredEventKeepsItsTransition() {
        var comment = new WorkflowEvent.IssueComment(ISSUE, "please rename the field");
        var redelivered = new WorkflowEvent.IssueComment(ISSUE, "please rename the field");

        // A webhook retry carries the same payload and must resume, not re-run.
        assertEquals(StepExecutor.transitionId("refine", comment), StepExecutor.transitionId("refine", redelivered));
    }

    @Test
    void anEventThatHappensOnceKeepsTheBareTransitionId() {
        var assigned = new WorkflowEvent.IssueAssigned(ISSUE, null);

        assertEquals("new:issue.assigned", StepExecutor.transitionId("new", assigned));
    }
}
