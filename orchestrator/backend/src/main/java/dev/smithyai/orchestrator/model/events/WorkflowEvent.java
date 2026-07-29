package dev.smithyai.orchestrator.model.events;

import dev.smithyai.orchestrator.model.*;
import java.util.List;

public sealed interface WorkflowEvent {
    RepoInfo info();

    sealed interface IssueScoped extends WorkflowEvent {
        IssueContext ctx();

        @Override
        default RepoInfo info() {
            return ctx().info();
        }
    }

    sealed interface PrScoped extends WorkflowEvent {
        PrContext prc();

        @Override
        default RepoInfo info() {
            return prc().info();
        }
    }

    // ── IssueScoped ─────────────────────────────
    record IssueAssigned(IssueContext ctx, String repoHtmlUrl) implements IssueScoped {}

    record IssueUnassigned(IssueContext ctx) implements IssueScoped {}

    record IssueComment(IssueContext ctx, String commentBody) implements IssueScoped {}

    record PlanApproved(IssueContext ctx, String approver) implements IssueScoped {}

    /** A comment authored by the smithy bot itself on an issue — consumed by the foreman only. */
    record BotPlanPosted(IssueContext ctx, String commentBody) implements IssueScoped {}

    // ── Standalone push ─────────────────────────
    record HumanPush(RepoInfo info, String branch) implements WorkflowEvent {}

    /** A push authored by the smithy bot on one of its branches — consumed by the foreman only. */
    record BotPush(RepoInfo info, String branch) implements WorkflowEvent {}

    // ── PrScoped ────────────────────────────────
    record PrConversationComment(PrContext prc, String commentUser, String commentBody, long commentId)
        implements PrScoped {}

    record PrReviewComment(PrContext prc, List<CommentData> comments, long commentId) implements PrScoped {}

    record ReviewSubmitted(PrContext prc, long reviewId, String reviewBody, String reviewer) implements PrScoped {}

    record PrFinalized(PrContext prc) implements PrScoped {}

    record PrUnassigned(PrContext prc) implements PrScoped {}

    record ReviewRequested(PrContext prc) implements PrScoped {}

    record PrMerged(PrContext prc) implements PrScoped {}

    // ── Standalone PR ───────────────────────────
    record PrClosed(RepoInfo info, int prNumber, boolean merged, String headBranch) implements WorkflowEvent {}

    // ── CI events ───────────────────────────────
    record CiFailure(RepoInfo info, CiRunInfo ciRun, String workflowName) implements WorkflowEvent {}

    record CiRecovery(RepoInfo info, CiRunInfo ciRun) implements WorkflowEvent {}
}
