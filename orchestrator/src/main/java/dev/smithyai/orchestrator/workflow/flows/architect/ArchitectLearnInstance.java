package dev.smithyai.orchestrator.workflow.flows.architect;

import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.model.CommentData;
import dev.smithyai.orchestrator.model.PrContext;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.service.claude.ClaudeParseException;
import dev.smithyai.orchestrator.service.claude.PromptRenderer;
import dev.smithyai.orchestrator.service.claude.dto.LearnResult;
import dev.smithyai.orchestrator.service.docker.*;
import dev.smithyai.orchestrator.service.docker.dto.ContainerConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerState;
import dev.smithyai.orchestrator.service.docker.dto.WorkflowType;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.dto.CommentEntry;
import dev.smithyai.orchestrator.service.vcs.dto.ReviewCommentEntry;
import dev.smithyai.orchestrator.service.vcs.dto.ReviewEntry;
import dev.smithyai.orchestrator.workflow.shared.AbstractWorkflowInstance;
import dev.smithyai.orchestrator.workflow.shared.StateMachine;
import dev.smithyai.orchestrator.workflow.shared.utils.Naming;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArchitectLearnInstance extends AbstractWorkflowInstance {

    private final StateMachine<LearnStage> stateMachine;
    private final String architectEmail;

    public ArchitectLearnInstance(
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
            LearnStage.NEW,
            null,
            architectEmail
        );
    }

    public ArchitectLearnInstance(
        ContainerSession session,
        VcsClient vcsClient,
        IssueTrackerClient issueTracker,
        PromptRenderer renderer,
        DockerConfig dockerConfig,
        VcsProviderConfig vcsConfig,
        List<String> tools,
        Runnable destroyCallback,
        LearnStage initialStage,
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
            null,
            tools,
            destroyCallback,
            existingSessionId
        );
        this.architectEmail = architectEmail;
        // @formatter:off
        this.stateMachine = StateMachine.builder(LearnStage.class, initialStage)
            .in(LearnStage.NEW)
                .on(WorkflowEvent.PrMerged.class, this::handlePrMerged).then(LearnStage.LEARNING)
                .done()
            .in(LearnStage.LEARNING)
                .on(WorkflowEvent.PrConversationComment.class, this::handlePrConversationComment).thenRemain()
                .done()
            .in(LearnStage.DONE)
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

    private void handlePrMerged(WorkflowEvent.PrMerged e) {
        String learnBranch = Naming.architectBranchName(e.prc().number(), "learn");
        session.initContainer(buildInit(e.prc(), learnBranch), LearnStage.NEW.value());
        learnTask(e.prc());
    }

    private void handlePrConversationComment(WorkflowEvent.PrConversationComment e) {
        var cd = CommentData.conversation(e.commentUser(), e.commentBody());
        session.updateState(ContainerState::touch);
        resumeSession(e.prc().info(), e.prc().number(), List.of(cd.toMap()));
    }

    // ── Init ─────────────────────────────────────────────────

    private ContainerConfig buildInit(PrContext prc, String learnBranch) {
        String contextRepo = Naming.contextRepoName(prc.info().repo());
        String contextCloneUrl = vcsClient.cloneUrl(prc.info().owner(), contextRepo);
        return ContainerConfig.builder()
            .cloneUrl(prc.info().cloneUrl())
            .branch(prc.headBranch())
            .sourceBranch(prc.baseBranch())
            .gitEmail(architectEmail)
            .gitUsername("The Architect")
            .vcsToken(vcsConfig.architectToken())
            .extraRepos(List.of(new ContainerConfig.ExtraRepo(contextCloneUrl, "/context-repo", learnBranch)))
            .workflowType(WorkflowType.ARCHITECT)
            .build();
    }

    // ── Tasks ────────────────────────────────────────────────

    private void learnTask(PrContext prc) {
        var info = prc.info();
        String contextRepo = Naming.contextRepoName(info.repo());
        String learnBranch = Naming.architectBranchName(prc.number(), "learn");

        try {
            log.info("Starting architect learn for PR #{} in {}", prc.number(), session.getContainerName());

            var conversation = fetchPrConversation(info.owner(), info.repo(), prc.number());

            String prompt = renderer.render(
                "architect_learn.md.j2",
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
                    "pr_merged",
                    prc.merged(),
                    "base_branch",
                    prc.baseBranch(),
                    "conversation",
                    conversation
                )
            );

            syncSessionId();

            LearnResult learnData;
            try {
                learnData = claude.send(prompt, LearnResult.class);
            } catch (ClaudeParseException e) {
                log.error("Failed to parse architect learn output for PR #{}", prc.number(), e);
                destroy();
                return;
            }

            String action = learnData.action() != null ? learnData.action() : "NONE";
            if ("UPDATE".equals(action)) {
                String title = learnData.title() != null ? learnData.title() : "Context update";
                String description = learnData.description() != null ? learnData.description() : "";

                var pushResult = session.exec(List.of("sh", "-c", "cd /context-repo && git push -u origin HEAD"));
                if (pushResult.exitCode() != 0) {
                    throw new RuntimeException("Failed to push context repo: " + pushResult.stderr());
                }

                String prLink = vcsClient.prUrl(
                    vcsConfig.resolvedExternalUrl(),
                    info.owner(),
                    info.repo(),
                    prc.number()
                );
                var contextPr = vcsClient.createPullRequest(
                    info.owner(),
                    contextRepo,
                    title,
                    learnBranch,
                    "main",
                    "Learned from [%s/%s#%d](%s): %s\n\n%s".formatted(
                        info.owner(),
                        info.repo(),
                        prc.number(),
                        prLink,
                        prc.title(),
                        description
                    ),
                    false
                );
                log.info("Created context repo PR #{} from learning on PR #{}", contextPr.number(), prc.number());
            } else {
                log.info("Architect found no context updates needed for PR #{}", prc.number());
                destroy();
            }

            log.info("Architect learn complete for PR #{}", prc.number());
        } catch (Exception e) {
            log.error("Architect learn task failed for PR #{}", prc.number(), e);
            try {
                destroy();
            } catch (Exception ignored) {}
        }
    }

    private void resumeSession(RepoInfo info, int prNumber, List<Map<String, Object>> commentDicts) {
        try {
            ContainerState state = session.readState();
            if (state.sessionId() == null) {
                log.warn("No session ID for {}, cannot resume", session.getContainerName());
                return;
            }

            String prompt = renderer.render(
                "architect_learn_comment.md.j2",
                Map.of("pr_number", prNumber, "comments", commentDicts)
            );
            var result = claude.send(prompt);
            syncSessionId();

            var commitResult = session.exec("smithy-commit-and-push", "Update context", "/context-repo");
            if (commitResult.exitCode() != 0) {
                log.warn("smithy-commit-and-push failed in {}: {}", session.getContainerName(), commitResult.stderr());
            }

            if (result != null && !result.strip().isBlank()) {
                vcsClient.createPrComment(info.owner(), info.repo(), prNumber, result);
            }
        } catch (Exception e) {
            log.error("Resume architect learn session failed for PR #{}", prNumber, e);
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    private List<Map<String, Object>> fetchPrConversation(String owner, String repo, int prNumber) {
        var entries = new ArrayList<Map<String, Object>>();
        addIssueComments(entries, owner, repo, prNumber);
        addReviewComments(entries, owner, repo, prNumber);
        entries.sort(Comparator.comparing(e -> e.getOrDefault("created_at", "").toString()));
        return entries;
    }

    private void addIssueComments(List<Map<String, Object>> entries, String owner, String repo, int prNumber) {
        try {
            var comments = vcsClient.getPrComments(owner, repo, prNumber);
            for (CommentEntry c : comments) {
                entries.add(
                    Map.of(
                        "user",
                        c.userLogin(),
                        "body",
                        c.body(),
                        "type",
                        "comment",
                        "created_at",
                        c.createdAt().toString()
                    )
                );
            }
        } catch (Exception e) {
            log.warn("Failed to fetch PR comments for PR #{}", prNumber, e);
        }
    }

    private void addReviewComments(List<Map<String, Object>> entries, String owner, String repo, int prNumber) {
        try {
            var reviews = vcsClient.getPrReviews(owner, repo, prNumber);
            for (ReviewEntry review : reviews) {
                String reviewUser = review.userLogin();
                String reviewBody = review.body() != null ? review.body() : "";
                String commitId = review.commitId() != null ? review.commitId() : "";

                if (!reviewBody.strip().isBlank()) {
                    var entry = new LinkedHashMap<String, Object>();
                    entry.put("user", reviewUser);
                    entry.put("body", reviewBody);
                    entry.put("type", "review");
                    entry.put("commit_id", commitId);
                    entry.put("created_at", review.submittedAt() != null ? review.submittedAt().toString() : "");
                    entries.add(entry);
                }

                long reviewId = review.id();
                if (reviewId > 0) {
                    try {
                        var inline = vcsClient.getReviewComments(owner, repo, prNumber, reviewId);
                        for (ReviewCommentEntry ic : inline) {
                            var entry = new LinkedHashMap<String, Object>();
                            entry.put("user", ic.userLogin());
                            entry.put("body", ic.body());
                            entry.put("type", "review_comment");
                            entry.put("path", ic.path() != null ? ic.path() : "");
                            entry.put("line", ic.position());
                            entry.put("commit_id", commitId);
                            entry.put("created_at", ic.createdAt().toString());
                            entries.add(entry);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to fetch review {} comments for PR #{}", reviewId, prNumber, e);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch reviews for PR #{}", prNumber, e);
        }
    }
}
