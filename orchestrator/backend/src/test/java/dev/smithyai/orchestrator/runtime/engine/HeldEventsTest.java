package dev.smithyai.orchestrator.runtime.engine;

import static org.junit.jupiter.api.Assertions.*;

import dev.smithyai.orchestrator.model.IssueContext;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HeldEventsTest {

    private static final RepoInfo REPO = new RepoInfo("acme", "platform", null, "jira", "jira");

    private static WorkflowEvent comment(String text) {
        return new WorkflowEvent.IssueComment(new IssueContext(REPO, "ECD-9", "t", "b", "main", ""), text);
    }

    private static WorkflowEvent approval() {
        return new WorkflowEvent.PlanApproved(new IssueContext(REPO, "ECD-9", "t", "b", "main", ""), "");
    }

    @Test
    void holdsInArrivalOrderAndDrainsOnce() {
        var held = new HeldEvents();
        assertEquals(new HeldEvents.Held(HeldEvents.Outcome.HELD, 1), held.hold("r", comment("one")));
        assertEquals(new HeldEvents.Held(HeldEvents.Outcome.HELD, 2), held.hold("r", approval()));
        assertTrue(held.hasAny("r"));
        assertEquals(Set.of("r"), held.runIds());

        var drained = held.drain("r");

        assertEquals(
            List.of("issue.commented", "issue.plan_approved"),
            drained.stream().map(WorkflowEvent::name).toList()
        );
        assertTrue(held.drain("r").isEmpty());
        assertFalse(held.hasAny("r"));
        assertTrue(held.runIds().isEmpty());
    }

    @Test
    void aRedeliveredEventIsNotHeldTwice() {
        var held = new HeldEvents();
        held.hold("r", approval());

        assertEquals(new HeldEvents.Held(HeldEvents.Outcome.DUPLICATE, 1), held.hold("r", approval()));
        // A different comment is a different occurrence.
        assertEquals(HeldEvents.Outcome.HELD, held.hold("r", comment("two")).outcome());
    }

    @Test
    void theQueueIsBounded() {
        var held = new HeldEvents();
        for (int i = 0; i < HeldEvents.CAP; i++) held.hold("r", comment("c" + i));

        assertEquals(
            new HeldEvents.Held(HeldEvents.Outcome.CAPPED, HeldEvents.CAP),
            held.hold("r", comment("one too many"))
        );
        assertEquals(HeldEvents.CAP, held.drain("r").size());
    }

    /** A hold racing a drain must land where the next drain finds it. */
    @Test
    void aHoldDuringADrainIsNotLost() throws Exception {
        var held = new HeldEvents();
        var threads = new java.util.ArrayList<Thread>();
        var drained = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < 20; i++) {
            final int n = i;
            threads.add(Thread.ofPlatform().start(() -> held.hold("r", comment("c" + n))));
            threads.add(Thread.ofPlatform().start(() -> drained.addAndGet(held.drain("r").size())));
        }
        for (var t : threads) t.join();
        drained.addAndGet(held.drain("r").size());

        assertEquals(20, drained.get());
    }
}
