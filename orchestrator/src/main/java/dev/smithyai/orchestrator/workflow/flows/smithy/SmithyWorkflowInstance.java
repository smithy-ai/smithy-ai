package dev.smithyai.orchestrator.workflow.flows.smithy;

import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.model.*;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.service.claude.ClaudeSession;
import dev.smithyai.orchestrator.service.claude.PromptRenderer;
import dev.smithyai.orchestrator.service.claude.dto.PlanResult;
import dev.smithyai.orchestrator.service.docker.*;
import dev.smithyai.orchestrator.service.docker.dto.ContainerConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerState;
import dev.smithyai.orchestrator.service.docker.dto.WorkflowType;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.dto.ReviewCommentEntry;
import dev.smithyai.orchestrator.workflow.shared.AbstractWorkflowInstance;
import dev.smithyai.orchestrator.workflow.shared.StateMachine;
import dev.smithyai.orchestrator.workflow.shared.utils.AttachmentHelper;
import dev.smithyai.orchestrator.workflow.shared.utils.Naming;
import dev.smithyai.orchestrator.workflow.shared.utils.PushHelper;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SmithyWorkflowInstance extends AbstractWorkflowInstance {

    private static final int MAX_CI_RETRIES = 5;

    private final String botUser;
    private final StateMachine<Stage> stateMachine;

    public SmithyWorkflowInstance(
        ContainerSession session,
        VcsClient vcsClient,
        IssueTrackerClient issueTracker,
        PromptRenderer renderer,
        DockerConfig dockerConfig,
        VcsProviderConfig vcsConfig,
        BotConfig botConfig,
        List<String> tools,
        Runnable destroyCallback
    ) {
        this(
            session,
            vcsClient,
            issueTracker,
            renderer,
            dockerConfig,
            vcsConfig,
            botConfig,
            tools,
            destroyCallback,
            Stage.NEW
        );
    }

    public SmithyWorkflowInstance(
        ContainerSession session,
        VcsClient vcsClient,
        IssueTrackerClient issueTracker,
        PromptRenderer renderer,
        DockerConfig dockerConfig,
        VcsProviderConfig vcsConfig,
        BotConfig botConfig,
        List<String> tools,
        Runnable destroyCallback,
        Stage initialStage
    ) {
        this(
            session,
            vcsClient,
            issueTracker,
            renderer,
            dockerConfig,
            vcsConfig,
            botConfig,
            tools,
            destroyCallback,
            initialStage,
            null
        );
    }

    public SmithyWorkflowInstance(
        ContainerSession session,
        VcsClient vcsClient,
        IssueTrackerClient issueTracker,
        PromptRenderer renderer,
        DockerConfig dockerConfig,
        VcsProviderConfig vcsConfig,
        BotConfig botConfig,
        List<String> tools,
        Runnable destroyCallback,
        Stage initialStage,
        String existingSessionId
    ) {
        super(
            session,
            vcsClient,
            issueTracker,
            renderer,
            dockerConfig,
            vcsConfig,
            tools,
            destroyCallback,
            existingSessionId
        );
        this.botUser = botConfig.resolvedSmithyUser();
        // @formatter:off
        this.stateMachine = StateMachine.builder(Stage.class, initialStage)
            .in(Stage.NEW)
                .on(WorkflowEvent.IssueAssigned.class, this::handleIssueAssigned).then(Stage.REFINE)
                .done()
            .in(Stage.REFINE)
                .on(WorkflowEvent.IssueComment.class, this::handleIssueComment).thenRemain()
                .on(WorkflowEvent.PlanApproved.class, this::handlePlanApproved).then(Stage.BUILD)
                .done()
            .in(Stage.BUILD)
                .on(WorkflowEvent.PrConversationComment.class, this::handlePrConversationComment).thenRemain()
                .on(WorkflowEvent.PrReviewComment.class, this::handlePrReviewComment).thenRemain()
                .on(WorkflowEvent.ReviewSubmitted.class, this::handleReviewSubmitted).thenRemain()
                .on(WorkflowEvent.HumanPush.class, this::handleHumanPush).thenRemain()
                .on(WorkflowEvent.CiFailure.class, this::handleCiFailure).thenRemain()
                .on(WorkflowEvent.CiRecovery.class, this::handleCiRecovery).thenRemain()
                .on(WorkflowEvent.PrFinalized.class, this::handlePrFinalized).then(Stage.DONE)
                .done()
            .in(Stage.DONE)
                .done()
            .build();
        // @formatter:on
    }

    // ── Unified event entry point ───────────────────────────

    @Override
    protected void handleEvent(WorkflowEvent event) {
        if (!stateMachine.canFire(event.getClass())) {
            log.debug("Ignoring {} in stage {}", event.getClass().getSimpleName(), stateMachine.state());
            return;
        }
        stateMachine.fire(event);
    }

    // ── Background Tasks ────────────────────────────────────

    private void handleIssueAssigned(WorkflowEvent.IssueAssigned e) {
        var ctx = e.ctx();
        try {
            if (session.exists()) {
                log.debug("Refine container {} already exists, skipping", session.getContainerName());
                return;
            }

            String branch = Naming.branchName(ctx.number(), ctx.title());
            String planPath = Naming.planFilePath(ctx.number());
            var info = ctx.info();

            log.info("Creating refine container {} for issue #{}", session.getContainerName(), ctx.number());
            var containerConfig = ContainerConfig.builder()
                .cloneUrl(info.cloneUrl())
                .branch(branch)
                .sourceBranch(ctx.baseBranch())
                .cacheVolumes(dockerConfig.getCacheVolumeMap())
                .workflowType(WorkflowType.SMITHY)
                .build();

            session.initContainer(containerConfig, "refine");

            // Fetch and inject attachments
            var attachments = AttachmentHelper.fetchAndInject(
                issueTracker,
                session,
                info.owner(),
                info.repo(),
                ctx.number()
            );

            // Render and invoke plan
            String prompt = renderer.render(
                "refinement.md.j2",
                Map.of(
                    "issue_number",
                    ctx.number(),
                    "issue_title",
                    ctx.title(),
                    "issue_body",
                    ctx.body() != null ? ctx.body() : "",
                    "plan_file_path",
                    planPath,
                    "attachments",
                    attachments
                )
            );

            claude.startPlan(prompt);
            syncSessionId();

            // Copy Claude's plan file to the expected path
            var planSrc = claude.latestPlanFile();
            if (planSrc.isEmpty()) {
                log.warn("Claude returned empty plan for issue #{}", ctx.number());
                issueTracker.createIssueComment(
                    info.owner(),
                    info.repo(),
                    ctx.number(),
                    "Failed to generate a development plan — please add a comment with more context and smithy will retry."
                );
                return;
            }

            var copyResult = session.exec(
                List.of("sh", "-c", "mkdir -p \"$(dirname \"$PLAN_PATH\")\" && cp \"$SRC\" \"$PLAN_PATH\""),
                Map.of("PLAN_PATH", planPath, "SRC", planSrc.get())
            );
            if (copyResult.exitCode() != 0) {
                throw new RuntimeException("Failed to copy plan file: " + copyResult.stderr());
            }

            // Extract open questions from the plan
            List<String> openQuestions = List.of();
            try {
                String extractPrompt = renderer.render(
                    "refinement_extract.md.j2",
                    Map.of("plan_file_path", planPath)
                );
                PlanResult planResult = claude.send(extractPrompt, PlanResult.class);
                openQuestions = planResult.openQuestions();
            } catch (Exception ex) {
                log.warn("Failed to extract open questions for issue #{}", ctx.number(), ex);
            }

            var pushResult = session.exec("smithy-commit-and-push", "Development plan for #" + ctx.number());
            if (pushResult.exitCode() != 0) {
                throw new RuntimeException("Failed to commit and push plan: " + pushResult.stderr());
            }

            // Post link to plan with open questions if any
            String publicBase = e.repoHtmlUrl();
            String planUrl = vcsClient.fileBrowseUrl(publicBase, branch, planPath);
            var comment = new StringBuilder("Development plan: [%s](%s)".formatted(planPath, planUrl));
            if (!openQuestions.isEmpty()) {
                comment.append("\n\n### Open Questions");
                for (String q : openQuestions) {
                    comment.append("\n- ").append(q);
                }
            }
            issueTracker.createIssueComment(
                info.owner(),
                info.repo(),
                ctx.number(),
                comment.toString()
            );

            log.info("Refinement complete for issue #{}", ctx.number());
        } catch (Exception ex) {
            log.error("Refinement task failed for issue #{}", ctx.number(), ex);
        }
    }

    private void handleIssueComment(WorkflowEvent.IssueComment e) {
        var ctx = e.ctx();
        try {
            session.updateState(ContainerState::touch);

            if (!session.exists()) return;

            ContainerState state = session.readState();
            if (state.sessionId() == null) {
                log.warn("No session ID for {}, cannot resume", session.getContainerName());
                return;
            }

            var info = ctx.info();
            var pullResult = session.exec("git", "pull", "--ff-only");
            if (pullResult.exitCode() != 0) {
                log.warn("git pull --ff-only failed in {}: {}", session.getContainerName(), pullResult.stderr());
            }

            var attachments = AttachmentHelper.fetchAndInject(
                issueTracker,
                session,
                info.owner(),
                info.repo(),
                ctx.number()
            );

            String prompt = renderer.render(
                "refinement_comment.md.j2",
                Map.of(
                    "issue_number",
                    ctx.number(),
                    "comment_body",
                    e.commentBody(),
                    "plan_file_path",
                    Naming.planFilePath(ctx.number()),
                    "attachments",
                    attachments
                )
            );

            claude.send(prompt);
            syncSessionId();

            var pushResult = session.exec("smithy-commit-and-push", "Update plan for #" + ctx.number());
            if (pushResult.exitCode() != 0) {
                log.warn("smithy-commit-and-push failed in {}: {}", session.getContainerName(), pushResult.stderr());
            }
        } catch (Exception ex) {
            log.error("Resume refinement failed for issue #{}", ctx.number(), ex);
        }
    }

    private void handlePlanApproved(WorkflowEvent.PlanApproved e) {
        var ctx = e.ctx();
        try {
            session.updateState(s -> s.withStage("build").touch());

            String branch = Naming.branchName(ctx.number(), ctx.title());
            String planPath = Naming.planFilePath(ctx.number());
            var info = ctx.info();

            // Rebase onto base branch
            log.info("Rebasing {} onto {}", session.getContainerName(), ctx.baseBranch());
            var rebaseResult = session.exec("smithy-rebase-onto", branch, ctx.baseBranch());
            if (rebaseResult.exitCode() != 0) {
                throw new RuntimeException("Rebase failed: " + rebaseResult.stderr());
            }

            // Create draft PR
            var pr = vcsClient.createPullRequest(
                info.owner(),
                info.repo(),
                ctx.title(),
                branch,
                ctx.baseBranch(),
                "fixes #" + ctx.number(),
                true
            );
            log.info("Created draft PR #{} for issue #{}", pr.number(), ctx.number());

            vcsClient.setPrAssignees(info.owner(), info.repo(), pr.number(), List.of(botUser));

            // Fetch attachments
            var attachments = AttachmentHelper.fetchAndInject(
                issueTracker,
                session,
                info.owner(),
                info.repo(),
                ctx.number()
            );

            // Start build session — new ClaudeSession for build phase
            newClaudeSession(SmithyWorkflowFactory.BUILD_TOOLS);
            String prompt = renderer.render(
                "building.md.j2",
                Map.of(
                    "issue_number",
                    ctx.number(),
                    "issue_title",
                    ctx.title(),
                    "plan_file_path",
                    planPath,
                    "attachments",
                    attachments
                )
            );

            claude.send(prompt);
            claude.ensureCommitted();
            syncSessionId();

            // Push
            PushHelper.pushWithRetry(session, claude, vcsClient, info.owner(), info.repo(), pr.number());

            // Request review
            String approver = e.approver();
            if (approver != null && !approver.isBlank()) {
                try {
                    vcsClient.requestReview(info.owner(), info.repo(), pr.number(), List.of(approver));
                    log.info("Requested review from {} on PR #{}", approver, pr.number());
                } catch (Exception ex) {
                    log.warn("Failed to request review from {} on PR #{}", approver, pr.number(), ex);
                }
            }

            log.info("Building started for issue #{}", ctx.number());
        } catch (Exception ex) {
            log.error("Transition to building failed for issue #{}", ctx.number(), ex);
        }
    }

    private void handlePrReviewComment(WorkflowEvent.PrReviewComment e) {
        session.updateState(ContainerState::touch);
        int issueId = Naming.parseIssueIdFromBranch(e.prc().headBranch());
        var commentDicts = new ArrayList<Map<String, Object>>();
        for (var cd : e.comments()) {
            commentDicts.add(cd.toMap());
        }
        String prompt = renderer.render(
            "review_comment.md.j2",
            Map.of("pr_number", e.prc().number(), "issue_number", issueId, "comments", commentDicts)
        );
        resumeBuild(e.prc().info(), issueId, e.prc().number(), prompt, false);
    }

    private void handlePrConversationComment(WorkflowEvent.PrConversationComment e) {
        int issueId = Naming.parseIssueIdFromBranch(e.prc().headBranch());

        // CI-fix approval check
        ContainerState state = session.readState();
        if (state.ciPaused() && e.commentBody().contains("\uD83D\uDC4D")) {
            session.updateState(ContainerState::resetCi);
            log.info("CI fix approved for issue #{}, resuming", issueId);

            String ciPrompt = renderer.render(
                "ci_failure.md.j2",
                Map.of("pr_number", e.prc().number(), "issue_number", issueId, "workflow_id", "")
            );
            session.updateState(ContainerState::touch);
            resumeBuild(e.prc().info(), issueId, e.prc().number(), ciPrompt, true);
            return;
        }

        var cd = CommentData.conversation(e.commentUser(), e.commentBody());
        var commentDicts = List.of(cd.toMap());
        String prompt = renderer.render(
            "review_comment.md.j2",
            Map.of("pr_number", e.prc().number(), "issue_number", issueId, "comments", commentDicts)
        );

        session.updateState(ContainerState::touch);
        resumeBuild(e.prc().info(), issueId, e.prc().number(), prompt, false);
    }

    private void handleReviewSubmitted(WorkflowEvent.ReviewSubmitted e) {
        session.updateState(ContainerState::touch);
        int issueId = Naming.parseIssueIdFromBranch(e.prc().headBranch());
        var info = e.prc().info();
        int prNumber = e.prc().number();

        try {
            var commentDicts = new ArrayList<Map<String, Object>>();
            List<ReviewCommentEntry> reviewComments;
            String reviewBody = e.reviewBody();

            if (e.reviewId() > 0) {
                reviewComments = vcsClient.getReviewComments(info.owner(), info.repo(), prNumber, e.reviewId());
            } else {
                var result = vcsClient.getLatestReviewComments(info.owner(), info.repo(), prNumber, e.reviewer());
                reviewComments = result.comments();
                reviewBody = result.reviewBody();
            }

            for (var rc : reviewComments) {
                commentDicts.add(
                    new CommentData(
                        rc.userLogin(),
                        rc.body(),
                        rc.path() != null ? rc.path() : "",
                        (int) rc.position()
                    ).toMap()
                );
            }

            if (reviewBody != null && !reviewBody.strip().isBlank()) {
                commentDicts.addFirst(CommentData.conversation(e.reviewer(), reviewBody).toMap());
            }

            if (commentDicts.isEmpty()) {
                log.info("Review on PR #{} has no comments, skipping", prNumber);
                return;
            }

            String prompt = renderer.render(
                "review_comment.md.j2",
                Map.of("pr_number", prNumber, "issue_number", issueId, "comments", commentDicts)
            );
            resumeBuild(info, issueId, prNumber, prompt, false);
        } catch (Exception ex) {
            log.error("Fetch and resume review failed for issue #{}", issueId, ex);
        }
    }

    private void handleHumanPush(WorkflowEvent.HumanPush e) {
        session.updateState(ContainerState::touch);
        try {
            var pr = vcsClient.findPrByHead(e.info().owner(), e.info().repo(), e.branch());

            if (pr != null && (pr.assignees() == null || !pr.assignees().contains(botUser))) {
                log.info("Smithy unassigned from PR #{}, ignoring human push", pr.number());
                return;
            }

            if (session.exists()) {
                log.info("Fetching latest changes in {} after human push", session.getContainerName());
                var fetchResult = session.exec("smithy-fetch-reset", e.branch());
                if (fetchResult.exitCode() != 0) {
                    throw new RuntimeException("smithy-fetch-reset failed: " + fetchResult.stderr());
                }
            } else {
                log.warn(
                    "Container {} missing from Docker after human push, destroying instance",
                    session.getContainerName()
                );
                destroy();
            }

            log.info("Container updated after human push on {}", e.branch());
        } catch (Exception ex) {
            log.error("Handle human push failed on branch {}", e.branch(), ex);
        }
    }

    private void handlePrFinalized(WorkflowEvent.PrFinalized e) {
        int issueId = Naming.parseIssueIdFromBranch(e.prc().headBranch());
        try {
            var cleanupResult = session.exec("smithy-cleanup-branch");
            if (cleanupResult.exitCode() != 0) {
                log.warn("smithy-cleanup-branch failed in {}: {}", session.getContainerName(), cleanupResult.stderr());
            }
            destroy();

            log.info("Issue #{} transitioned to Review", issueId);
        } catch (Exception ex) {
            log.error("Transition to review failed for issue #{}", issueId, ex);
        }
    }

    private void handleCiFailure(WorkflowEvent.CiFailure e) {
        var ciRun = e.ciRun();
        var info = e.info();
        int issueId = Naming.parseIssueIdFromBranch(ciRun.headBranch());

        ContainerState state = session.readState();
        if (state.ciPaused()) {
            log.info("CI fixing paused for issue #{}, waiting for approval", issueId);
            return;
        }

        state = state.incrementCiRetryCount();
        if (state.ciRetryCount() > MAX_CI_RETRIES) {
            state = state.withCiPaused(true);
            session.writeState(state);
            if (ciRun.prNumber() != null) {
                vcsClient.createPrComment(
                    info.owner(),
                    info.repo(),
                    ciRun.prNumber(),
                    "Reached maximum 5 CI fix attempts. Continue debugging? Reply with \uD83D\uDC4D for OK"
                );
            }
            log.info("CI fix attempts exhausted for issue #{}, pausing", issueId);
            return;
        }
        session.writeState(state);

        String ciPrompt = renderer.render(
            "ci_failure.md.j2",
            Map.of(
                "pr_number",
                ciRun.prNumber() != null ? ciRun.prNumber() : 0,
                "issue_number",
                issueId,
                "workflow_id",
                e.workflowName()
            )
        );
        session.updateState(ContainerState::touch);
        resumeBuild(info, issueId, ciRun.prNumber(), ciPrompt, true);
    }

    private void handleCiRecovery(WorkflowEvent.CiRecovery e) {
        session.updateState(ContainerState::resetCi);
        log.info("CI recovered for branch {}, reset failure counter", e.ciRun().headBranch());
    }

    // ── Private helpers ─────────────────────────────────────

    private void newClaudeSession(List<String> tools) {
        this.claude = new ClaudeSession(session, tools);
    }

    private void resumeBuild(RepoInfo info, int issueId, Integer prNumber, String prompt, boolean skipAssignmentCheck) {
        try {
            if (!session.exists()) return;

            if (!skipAssignmentCheck && prNumber != null) {
                if (!vcsClient.isAssigned(info.owner(), info.repo(), prNumber, botUser)) {
                    log.info("Smithy unassigned from PR #{}, skipping resume build", prNumber);
                    return;
                }
            }

            ContainerState state = session.readState();
            if (state.sessionId() == null) {
                log.warn("No session ID for {}, cannot resume", session.getContainerName());
                return;
            }

            log.info("Resuming build session in {}", session.getContainerName());
            claude.send(prompt);
            claude.ensureCommitted();
            syncSessionId();

            PushHelper.pushWithRetry(session, claude, vcsClient, info.owner(), info.repo(), prNumber);
        } catch (Exception ex) {
            log.error("Resume build failed for issue #{}", issueId, ex);
        }
    }
}
