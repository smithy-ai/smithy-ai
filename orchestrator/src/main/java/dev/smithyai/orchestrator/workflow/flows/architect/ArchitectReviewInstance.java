package dev.smithyai.orchestrator.workflow.flows.architect;

import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.model.CommentData;
import dev.smithyai.orchestrator.model.PrContext;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.service.claude.ClaudeParseException;
import dev.smithyai.orchestrator.service.claude.PromptRenderer;
import dev.smithyai.orchestrator.service.claude.dto.ReviewResult;
import dev.smithyai.orchestrator.service.docker.*;
import dev.smithyai.orchestrator.service.docker.dto.ContainerConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerState;
import dev.smithyai.orchestrator.service.docker.dto.WorkflowType;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.dto.InlineComment;
import dev.smithyai.orchestrator.workflow.shared.AbstractWorkflowInstance;
import dev.smithyai.orchestrator.workflow.shared.StateMachine;
import dev.smithyai.orchestrator.workflow.shared.utils.Naming;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArchitectReviewInstance extends AbstractWorkflowInstance {

    private final StateMachine<ReviewStage> stateMachine;
    private final String architectEmail;

    public ArchitectReviewInstance(
        ContainerSession session,
        VcsClient vcsClient,
        IssueTrackerClient issueTracker,
        PromptRenderer renderer,
        DockerConfig dockerConfig,
        VcsProviderConfig vcsConfig,
        List<String> tools,
        Runnable destroyCallback,
        String architectEmail
    ) {
        this(
            session,
            vcsClient,
            issueTracker,
            renderer,
            dockerConfig,
            vcsConfig,
            tools,
            destroyCallback,
            ReviewStage.NEW,
            null,
            architectEmail
        );
    }

    public ArchitectReviewInstance(
        ContainerSession session,
        VcsClient vcsClient,
        IssueTrackerClient issueTracker,
        PromptRenderer renderer,
        DockerConfig dockerConfig,
        VcsProviderConfig vcsConfig,
        List<String> tools,
        Runnable destroyCallback,
        ReviewStage initialStage,
        String existingSessionId,
        String architectEmail
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
        this.architectEmail = architectEmail;
        // @formatter:off
        this.stateMachine = StateMachine.builder(ReviewStage.class, initialStage)
            .in(ReviewStage.NEW)
                .on(WorkflowEvent.ReviewRequested.class, this::handleReviewRequested).then(ReviewStage.REVIEWING)
                .done()
            .in(ReviewStage.REVIEWING)
                .on(WorkflowEvent.PrConversationComment.class, this::handlePrConversationComment).thenRemain()
                .done()
            .in(ReviewStage.DONE)
                .done()
            .build();
        // @formatter:on
    }

    @Override
    protected void handleEvent(WorkflowEvent event) {
        if (!stateMachine.canFire(event.getClass())) {
            log.debug("Ignoring {} in stage {}", event.getClass().getSimpleName(), stateMachine.state());
            return;
        }
        stateMachine.fire(event);
    }

    // ── Event handlers ──────────────────────────────────────

    private void handleReviewRequested(WorkflowEvent.ReviewRequested e) {
        session.initContainer(buildInit(e.prc()), ReviewStage.NEW.value());
        reviewTask(e.prc());
    }

    private void handlePrConversationComment(WorkflowEvent.PrConversationComment e) {
        var cd = CommentData.conversation(e.commentUser(), e.commentBody());
        resumeSession(e.prc().info(), e.prc().number(), List.of(cd.toMap()));
    }

    // ── Init ─────────────────────────────────────────────────

    private ContainerConfig buildInit(PrContext prc) {
        String contextRepo = Naming.contextRepoName(prc.info().repo());
        String contextCloneUrl = vcsClient.cloneUrl(prc.info().owner(), contextRepo);
        return ContainerConfig.builder()
            .cloneUrl(prc.info().cloneUrl())
            .branch(prc.headBranch())
            .sourceBranch(prc.baseBranch())
            .gitEmail(architectEmail)
            .gitUsername("The Architect")
            .vcsToken(vcsConfig.architectToken())
            .extraRepos(List.of(new ContainerConfig.ExtraRepo(contextCloneUrl, "/context-repo", "main")))
            .workflowType(WorkflowType.ARCHITECT)
            .build();
    }

    // ── Tasks ────────────────────────────────────────────────

    private void reviewTask(PrContext prc) {
        var info = prc.info();
        String contextRepo = Naming.contextRepoName(info.repo());

        try {
            log.info("Starting architect review for PR #{} in {}", prc.number(), session.getContainerName());

            String prompt = renderer.render(
                "architect_review.md.j2",
                Map.of(
                    "owner",
                    info.owner(),
                    "repo",
                    info.repo(),
                    "context_repo",
                    contextRepo,
                    "pr_number",
                    prc.number(),
                    "pr_title",
                    prc.title(),
                    "pr_body",
                    prc.body(),
                    "base_branch",
                    prc.baseBranch()
                )
            );

            syncSessionId();

            try {
                var reviewData = claude.send(prompt, ReviewResult.class);
                postReview(info.owner(), info.repo(), prc.number(), reviewData);
            } catch (ClaudeParseException e) {
                log.error("Failed to parse architect review output for PR #{}", prc.number(), e);
                if (e.getRawContent() != null && !e.getRawContent().isBlank()) {
                    vcsClient.createPrComment(info.owner(), info.repo(), prc.number(), e.getRawContent());
                }
            }

            destroy();
            log.info("Architect review complete for PR #{}", prc.number());
        } catch (Exception e) {
            log.error("Architect review task failed for PR #{}", prc.number(), e);
            try {
                destroy();
            } catch (Exception ignored) {}
        }
    }

    private void resumeSession(
        dev.smithyai.orchestrator.model.RepoInfo info,
        int prNumber,
        List<Map<String, Object>> commentDicts
    ) {
        try {
            ContainerState state = session.readState();
            if (state.sessionId() == null) {
                log.warn("No session ID for {}, cannot resume", session.getContainerName());
                return;
            }

            String prompt = renderer.render(
                "architect_review_comment.md.j2",
                Map.of("pr_number", prNumber, "comments", commentDicts)
            );
            syncSessionId();

            try {
                var responseData = claude.send(prompt, ReviewResult.class);
                postReview(info.owner(), info.repo(), prNumber, responseData);
            } catch (ClaudeParseException e) {
                if (e.getRawContent() != null && !e.getRawContent().isBlank()) {
                    vcsClient.createPrComment(info.owner(), info.repo(), prNumber, e.getRawContent());
                }
            }
        } catch (Exception e) {
            log.error("Resume architect review session failed for PR #{}", prNumber, e);
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    private void postReview(String owner, String repo, int prNumber, ReviewResult reviewData) {
        String summary = reviewData.summary() != null ? reviewData.summary() : "";
        var reviewComments = new ArrayList<InlineComment>();
        if (reviewData.comments() != null) {
            for (var c : reviewData.comments()) {
                if (c.path() != null && !c.path().isBlank()) {
                    reviewComments.add(new InlineComment(c.path(), c.body() != null ? c.body() : "", c.line()));
                }
            }
        }
        if (!summary.isBlank() || !reviewComments.isEmpty()) {
            vcsClient.createPullReview(
                owner,
                repo,
                prNumber,
                summary,
                "COMMENT",
                reviewComments.isEmpty() ? null : reviewComments
            );
        }
    }
}
