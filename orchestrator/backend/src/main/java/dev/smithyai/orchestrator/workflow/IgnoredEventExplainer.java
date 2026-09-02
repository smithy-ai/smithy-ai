package dev.smithyai.orchestrator.workflow;

import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.engine.RunEngine;
import dev.smithyai.orchestrator.runtime.store.CorrelationKind;
import dev.smithyai.orchestrator.runtime.store.Run;
import dev.smithyai.orchestrator.runtime.store.RunRecorder;
import dev.smithyai.orchestrator.runtime.store.RunStatus;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import dev.smithyai.orchestrator.service.vcs.IssueTrackers;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Says why nothing happened.
 *
 * <p>A person assigning an issue, approving a plan or asking a question in a
 * comment is talking to the bot. When no workflow reacts — the run completed,
 * the current stage has no ear for that event, no workflow claims the project —
 * the engine used to drop the event at DEBUG level, and the person got
 * silence. Every one of those silences was eventually investigated by hand,
 * at a cost of hours; each investigation ended in a one-line explanation that
 * this class now posts up front.
 *
 * <p>Only human gestures on issues are answered: assignment, plan approval,
 * comment. Machine traffic (pushes, CI, PR events) goes unhandled all the time
 * and legitimately so. A comment on an issue no run has ever owned is a human
 * conversation and none of the bot's business — that one stays silent.
 */
@Slf4j
@Component
public class IgnoredEventExplainer {

    private final RunStore store;
    private final IssueTrackers trackers;

    public IgnoredEventExplainer(RunStore store, IssueTrackers trackers) {
        this.store = store;
        this.trackers = trackers;
    }

    public void explainIfIgnored(WorkflowEvent event, List<RunEngine.Outcome> outcomes) {
        if (outcomes.stream().anyMatch(RunEngine.Outcome::handled)) return;
        if (!(event instanceof WorkflowEvent.IssueScoped scoped)) return;

        String gesture = switch (event) {
            case WorkflowEvent.IssueAssigned ignored -> "assignment";
            case WorkflowEvent.PlanApproved ignored -> "plan approval";
            case WorkflowEvent.IssueComment ignored -> "comment";
            default -> null;
        };
        if (gesture == null) return;

        var ctx = scoped.ctx();
        var owner = store.findByCorrelation(
            CorrelationKind.ISSUE,
            RunRecorder.issueRef(ctx.info().owner(), ctx.info().repo(), ctx.issueRef())
        );

        String reason = reasonFor(gesture, owner);
        if (reason == null) return;

        try {
            trackers
                .forConnector("", event.source())
                .createIssueComment(ctx.info().owner(), ctx.info().repo(), ctx.issueRef(), reason);
            log.info("Explained an ignored {} on {}#{}", gesture, ctx.info().fullName(), ctx.issueRef());
        } catch (RuntimeException e) {
            // The explanation is a courtesy; failing to deliver it must not
            // make the webhook fail and get redelivered.
            log.warn("Could not explain an ignored {} on {}#{}", gesture, ctx.info().fullName(), ctx.issueRef(), e);
        }
    }

    private String reasonFor(String gesture, Optional<Run> owner) {
        if (owner.isEmpty()) {
            return switch (gesture) {
                case "assignment" -> "I can't take this issue: no workflow here claimed the assignment. " +
                "That usually means this project or repository is outside every configured workflow's scope. " +
                "Saying so rather than staying silent.";
                case "plan approval" -> "I saw the approval, but no run exists for this issue — there is nothing " +
                "to approve yet. Assign me first, and I'll plan it and ask for approval here.";
                // An issue no run has ever owned is a human conversation.
                default -> null;
            };
        }

        Run run = owner.get();
        if (run.status() == RunStatus.COMPLETED) {
            return ("I didn't act on that %s: my run for this issue already completed, and delivered work is not " +
                "redone on a stray event. To start a fresh round, remove me from the assignees and then assign " +
                "me again.").formatted(gesture);
        }
        if (run.isTerminal()) {
            return ("I didn't act on that %s: my run for this issue was %s. Assign me again to restart it from " +
                "the beginning.").formatted(gesture, run.status().value());
        }

        // An assignment landing on a run that is already working IS honoured —
        // that run exists because of it. Trackers redeliver the assignment
        // webhook when a long planning turn outlives their patience, and the
        // redelivery queues up behind the run's lock; explaining it reads as
        // the bot apologising for doing its job. Observed live: two of these
        // half a second after the plan was posted.
        if ("assignment".equals(gesture)) return null;

        String waits = store
            .findPendingWaits(run.id())
            .stream()
            .map(wait -> wait.waitKey())
            .collect(Collectors.joining(", "));
        return ("I saw the %s, but I'm mid-way through this issue (state '%s') and that stage doesn't react to it. %s")
            .formatted(
                gesture,
                run.state(),
                waits.isEmpty()
                    ? "The current stage finishes on its own; what happens next will be posted here."
                    : "It is waiting on: " + waits + "."
            );
    }
}
