package dev.smithyai.orchestrator.model.events;

import dev.smithyai.orchestrator.model.*;
import java.util.List;

/**
 * A normalized event, provider-neutral by the time it reaches a workflow.
 *
 * <p>Deliberately not sealed: a sealed hierarchy means every new event source
 * has to edit this file, and it is what let flow-specific events end up in the
 * shared model. Routing keys on {@link #name()} — a stable string — rather than
 * on the Java type, so a workflow definition can name the events it wants
 * without the engine knowing the record.
 */
public interface WorkflowEvent {
    RepoInfo info();

    /**
     * The event's routing name, e.g. {@code issue.assigned}. Provider adapters
     * agree on these; workflows match on them.
     */
    String name();

    interface IssueScoped extends WorkflowEvent {
        IssueContext ctx();

        @Override
        default RepoInfo info() {
            return ctx().info();
        }
    }

    interface PrScoped extends WorkflowEvent {
        PrContext prc();

        @Override
        default RepoInfo info() {
            return prc().info();
        }
    }

    // ── IssueScoped ─────────────────────────────
    record IssueAssigned(IssueContext ctx, String repoHtmlUrl) implements IssueScoped {
        @Override
        public String name() {
            return "issue.assigned";
        }
    }

    record IssueUnassigned(IssueContext ctx) implements IssueScoped {
        @Override
        public String name() {
            return "issue.unassigned";
        }
    }

    record IssueComment(IssueContext ctx, String commentBody) implements IssueScoped {
        @Override
        public String name() {
            return "issue.commented";
        }
    }

    /**
     * The approval gate. Still a distinct type while the flows are Java; a
     * definition expresses it as {@code issue.labeled} plus a predicate on the
     * configured label.
     */
    record PlanApproved(IssueContext ctx, String approver) implements IssueScoped {
        @Override
        public String name() {
            return "issue.plan_approved";
        }
    }

    // ── Standalone push ─────────────────────────
    record HumanPush(RepoInfo info, String branch) implements WorkflowEvent {
        @Override
        public String name() {
            return "push.human";
        }
    }

    // ── PrScoped ────────────────────────────────
    record PrConversationComment(
        PrContext prc,
        String commentUser,
        String commentBody,
        long commentId,
        String discussionId
    ) implements PrScoped {
        @Override
        public String name() {
            return "pr.commented";
        }
    }

    record PrReviewComment(
        PrContext prc,
        List<CommentData> comments,
        long commentId,
        String discussionId
    ) implements PrScoped {
        @Override
        public String name() {
            return "pr.review_commented";
        }
    }

    record ReviewSubmitted(PrContext prc, long reviewId, String reviewBody, String reviewer) implements PrScoped {
        @Override
        public String name() {
            return "pr.review_submitted";
        }
    }

    record PrFinalized(PrContext prc) implements PrScoped {
        @Override
        public String name() {
            return "pr.ready_for_review";
        }
    }

    record PrUnassigned(PrContext prc) implements PrScoped {
        @Override
        public String name() {
            return "pr.unassigned";
        }
    }

    record ReviewRequested(PrContext prc) implements PrScoped {
        @Override
        public String name() {
            return "pr.review_requested";
        }
    }

    record PrMerged(PrContext prc) implements PrScoped {
        @Override
        public String name() {
            return "pr.merged";
        }
    }

    // ── Standalone PR ───────────────────────────
    record PrClosed(RepoInfo info, int prNumber, boolean merged, String headBranch) implements WorkflowEvent {
        @Override
        public String name() {
            return "pr.closed";
        }
    }

    // ── CI events ───────────────────────────────
    record CiFailure(RepoInfo info, CiRunInfo ciRun, String workflowName) implements WorkflowEvent {
        @Override
        public String name() {
            return "ci.failed";
        }
    }

    record CiRecovery(RepoInfo info, CiRunInfo ciRun) implements WorkflowEvent {
        @Override
        public String name() {
            return "ci.recovered";
        }
    }

    // ── Internal ────────────────────────────────

    /**
     * One run telling another something, without going out through the VCS.
     *
     * <p>The only event here that no provider produces. A child used to reach
     * its parent by posting a comment the parent string-matched back out of the
     * bot's own output; a definition names this as {@code signal:<name>} the
     * same way it names any other event.
     *
     * @param info the repository the sender was working in, so a signal reads
     *             like any other event in a template
     */
    record Signal(RepoInfo info, String signal, java.util.Map<String, Object> payload) implements WorkflowEvent {
        @Override
        public String name() {
            return "signal:" + signal;
        }
    }

    /**
     * A burst of the same event, delivered together.
     *
     * <p>Routes exactly like one of them, because it is one of them as far as a
     * definition is concerned — the difference is that the steps can see all of
     * them, so a reviewer's four comments become one agent turn and one commit
     * rather than four of each.
     *
     * @param latest the event that closed the batch, and the one whose fields a
     *               template reads when it does not care about the rest
     */
    record Batch(WorkflowEvent latest, List<WorkflowEvent> events) implements WorkflowEvent {
        @Override
        public RepoInfo info() {
            return latest.info();
        }

        @Override
        public String name() {
            return latest.name();
        }
    }
}
