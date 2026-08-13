package dev.smithyai.orchestrator.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.config.WorkflowPolicyConfig;
import dev.smithyai.orchestrator.model.*;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.dto.PrData;
import dev.smithyai.orchestrator.util.Naming;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GitHubEventMapper {

    private final BotConfig botConfig;
    private final VcsProviderConfig vcsConfig;
    private final VcsClient smithyClient;
    private final String botUser;
    private final String smithyEmail;
    private final String planApprovedLabel;
    private final String branchPrefix;

    public GitHubEventMapper(
        BotConfig botConfig,
        VcsProviderConfig vcsConfig,
        WorkflowPolicyConfig workflowPolicy,
        VcsClient smithyClient
    ) {
        this.botConfig = botConfig;
        this.vcsConfig = vcsConfig;
        this.smithyClient = smithyClient;
        this.botUser = botConfig.resolvedSmithyUser();
        this.smithyEmail = botConfig.resolvedSmithyEmail();
        this.planApprovedLabel = workflowPolicy.resolvedPlanApprovedLabel();
        this.branchPrefix = workflowPolicy.resolvedBranchPrefix();
        log.info(
            "GitHubEventMapper initialized: smithyBot='{}', architectBot='{}'",
            botUser,
            botConfig.resolvedArchitectUser()
        );
    }

    public WorkflowEvent map(String eventType, JsonNode payload) {
        return switch (eventType) {
            case "issues" -> mapIssueEvent(payload);
            case "issue_comment" -> mapIssueComment(payload);
            case "push" -> mapPush(payload);
            case "pull_request" -> mapPullRequest(payload);
            case "pull_request_review" -> mapPullRequestReview(payload);
            case "pull_request_review_comment" -> mapPullRequestReviewComment(payload);
            case "workflow_run" -> mapWorkflowRun(payload);
            default -> {
                log.debug("Unhandled GitHub event type: {}", eventType);
                yield null;
            }
        };
    }

    // ── Issues ──────────────────────────────────────────────

    private WorkflowEvent mapIssueEvent(JsonNode payload) {
        String action = payload.path("action").asText("");
        return switch (action) {
            case "assigned" -> mapIssueAssigned(payload);
            case "unassigned" -> mapIssueUnassigned(payload);
            case "labeled" -> mapIssueLabeled(payload);
            default -> null;
        };
    }

    private WorkflowEvent mapIssueAssigned(JsonNode payload) {
        if (!isUserAssigned(payload, botUser)) return null;
        if (!"open".equals(payload.path("issue").path("state").asText(""))) return null;

        var ctx = extractIssue(payload);
        String repoHtmlUrl = payload.path("repository").path("html_url").asText("");
        return new WorkflowEvent.IssueAssigned(ctx, repoHtmlUrl);
    }

    private WorkflowEvent mapIssueUnassigned(JsonNode payload) {
        if (isUserAssigned(payload, botUser)) return null;
        var ctx = extractIssue(payload);
        return new WorkflowEvent.IssueUnassigned(ctx);
    }

    private WorkflowEvent mapIssueLabeled(JsonNode payload) {
        String labelName = payload.path("label").path("name").asText("");
        if (!planApprovedLabel.equals(labelName)) return null;

        String approver = payload.path("sender").path("login").asText("");
        var ctx = extractIssue(payload);
        return new WorkflowEvent.PlanApproved(ctx, approver);
    }

    // ── Issue comments ──────────────────────────────────────

    private WorkflowEvent mapIssueComment(JsonNode payload) {
        if (!"created".equals(payload.path("action").asText(""))) return null;
        String commentUser = payload.path("comment").path("user").path("login").asText("");
        if (botUser.equals(commentUser)) return null;

        var issue = payload.path("issue");
        // GitHub sends PR conversation comments as issue_comment events
        var pullRequest = issue.path("pull_request");
        if (!pullRequest.isMissingNode() && !pullRequest.isNull()) {
            return mapPrConversationFromIssueComment(payload, commentUser);
        }

        var ctx = extractIssue(payload);
        String commentBody = payload.path("comment").path("body").asText("");
        return new WorkflowEvent.IssueComment(ctx, commentBody);
    }

    private WorkflowEvent mapPrConversationFromIssueComment(JsonNode payload, String commentUser) {
        var info = repoInfo(payload);
        int prNumber = payload.path("issue").path("number").asInt();
        String commentBody = payload.path("comment").path("body").asText("");

        // Emitted as a fact. This used to fetch the PR from the API on the
        // webhook thread just to read the head branch and decide whether the
        // event was worth emitting; routing resolves the owning session from
        // the PR correlation instead.
        var issue = payload.path("issue");
        var prc = new PrContext(
            info,
            prNumber,
            issue.path("title").asText(""),
            issue.path("body").asText(""),
            false,
            "",
            ""
        );
        return new WorkflowEvent.PrConversationComment(
            prc,
            commentUser,
            commentBody,
            payload.path("comment").path("id").asLong(0),
            ""
        );
    }

    // ── Push ────────────────────────────────────────────────

    private WorkflowEvent mapPush(JsonNode payload) {
        String ref = payload.path("ref").asText("");
        String branch = ref.replaceFirst("^refs/heads/", "");
        if (!isWorkBranch(branch)) return null;

        var commits = payload.path("commits");
        boolean isHuman = false;
        if (commits.isArray()) {
            for (var c : commits) {
                // GitHub uses author.email for the commit author
                String email = c.path("author").path("email").asText("");
                if (!smithyEmail.equals(email)) {
                    isHuman = true;
                    break;
                }
            }
        }
        if (!isHuman) return null;

        var info = repoInfo(payload);
        return new WorkflowEvent.HumanPush(info, branch);
    }

    // ── Pull Requests ────────────────────────────────────────

    private WorkflowEvent mapPullRequest(JsonNode payload) {
        String action = payload.path("action").asText("");
        return switch (action) {
            case "review_requested" -> mapReviewRequested(payload);
            case "ready_for_review" -> mapPrReadyForReview(payload);
            case "closed" -> mapPrClosed(payload);
            case "unassigned" -> mapPrUnassigned(payload);
            default -> null;
        };
    }

    private WorkflowEvent mapReviewRequested(JsonNode payload) {
        String reviewer = payload.path("requested_reviewer").path("login").asText("");
        if (!reviewer.equals(botConfig.resolvedArchitectUser())) return null;

        var info = repoInfo(payload);
        var prc = extractPr(info, payload.path("pull_request"));
        return new WorkflowEvent.ReviewRequested(prc);
    }

    // GitHub sends ready_for_review when a draft PR is marked as ready — equivalent to PrFinalized
    private WorkflowEvent mapPrReadyForReview(JsonNode payload) {
        var pr = payload.path("pull_request");
        String headBranch = pr.path("head").path("ref").asText("");
        String issueRef = Naming.parseIssueRefFromBranch(headBranch);
        if (issueRef == null) return null;

        var info = repoInfo(payload);
        var prc = extractPr(info, pr);
        return new WorkflowEvent.PrFinalized(prc);
    }

    private WorkflowEvent mapPrClosed(JsonNode payload) {
        var pr = payload.path("pull_request");
        boolean merged = pr.path("merged").asBoolean(false);
        var info = repoInfo(payload);
        String headBranch = pr.path("head").path("ref").asText("");

        // Merged source PRs are what the architect learns from; its own context
        // PRs only drive cleanup and are recognised by branch, not repo name.
        if (merged && !Naming.isArchitectBranch(headBranch)) {
            var prc = extractPr(info, pr);
            return new WorkflowEvent.PrMerged(prc);
        }

        int prNumber = pr.path("number").asInt();
        return new WorkflowEvent.PrClosed(info, prNumber, merged, headBranch);
    }

    private WorkflowEvent mapPrUnassigned(JsonNode payload) {
        var pr = payload.path("pull_request");
        String headBranch = pr.path("head").path("ref").asText("");
        if (!isWorkBranch(headBranch)) return null;

        String issueRef = Naming.parseIssueRefFromBranch(headBranch);
        if (issueRef == null) return null;

        var assignees = pr.path("assignees");
        if (assignees.isArray()) {
            for (var a : assignees) {
                if (botUser.equals(a.path("login").asText(""))) return null;
            }
        }

        var info = repoInfo(payload);
        var prc = extractPr(info, pr);
        return new WorkflowEvent.PrUnassigned(prc);
    }

    // ── Pull Request Reviews ─────────────────────────────────

    private WorkflowEvent mapPullRequestReview(JsonNode payload) {
        if (!"submitted".equals(payload.path("action").asText(""))) return null;

        var review = payload.path("review");
        String reviewUser = review.path("user").path("login").asText("");
        if (botUser.equals(reviewUser)) return null;

        var pr = payload.path("pull_request");
        String headBranch = pr.path("head").path("ref").asText("");
        if (!isWorkBranch(headBranch)) return null;

        String issueRef = Naming.parseIssueRefFromBranch(headBranch);
        if (issueRef == null) return null;

        var info = repoInfo(payload);
        long reviewId = review.path("id").asLong();
        String reviewBody = review.path("body").asText("");
        var prc = extractPr(info, pr);
        return new WorkflowEvent.ReviewSubmitted(prc, reviewId, reviewBody, reviewUser);
    }

    // ── Pull Request Review Comments ─────────────────────────

    private WorkflowEvent mapPullRequestReviewComment(JsonNode payload) {
        if (!"created".equals(payload.path("action").asText(""))) return null;

        var comment = payload.path("comment");
        String commentUser = comment.path("user").path("login").asText("");
        if (botUser.equals(commentUser)) return null;

        var pr = payload.path("pull_request");
        String headBranch = pr.path("head").path("ref").asText("");

        // Comments on the architect's own context PR route back to its learn session.
        if (Naming.isArchitectBranch(headBranch) && !commentUser.equals(botConfig.resolvedArchitectUser())) {
            var info = repoInfo(payload);
            var prc = extractPr(info, pr);
            return new WorkflowEvent.PrConversationComment(
                prc,
                commentUser,
                comment.path("body").asText(""),
                comment.path("id").asLong(0),
                ""
            );
        }

        // GitHub also emits these for comments that are included in a submitted review.
        // Smithy handles review feedback from pull_request_review so all inline comments
        // for a review are fetched and processed once.
        return null;
    }

    // ── Workflow Run (CI) ────────────────────────────────────

    private WorkflowEvent mapWorkflowRun(JsonNode payload) {
        if (!"completed".equals(payload.path("action").asText(""))) return null;

        var run = payload.path("workflow_run");
        String conclusion = run.path("conclusion").asText("");
        if (!"failure".equals(conclusion) && !"success".equals(conclusion)) return null;

        String headBranch = run.path("head_branch").asText("");
        var info = repoInfo(payload);

        // Try to find the associated PR number
        Integer prNumber = null;
        var prs = run.path("pull_requests");
        if (prs.isArray() && !prs.isEmpty()) {
            prNumber = prs.get(0).path("number").asInt();
        } else if (!headBranch.isBlank()) {
            try {
                PrData pr = smithyClient.findPrByHead(info.owner(), info.repo(), headBranch);
                if (pr != null) prNumber = pr.number();
            } catch (Exception e) {
                log.warn("Failed to find PR for branch {} in workflow_run event", headBranch, e);
            }
        }

        var ciRun = new CiRunInfo(headBranch, prNumber);

        if ("failure".equals(conclusion)) {
            if (!isWorkBranch(headBranch)) {
                log.info("CI failure on non-smithy branch {}, ignoring", headBranch);
                return null;
            }
            String workflowName = run.path("name").asText("");
            return new WorkflowEvent.CiFailure(info, ciRun, workflowName);
        } else {
            return new WorkflowEvent.CiRecovery(info, ciRun);
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    private RepoInfo repoInfo(JsonNode payload) {
        return Naming.repoInfo(payload, vcsConfig.resolvedUrl(), RepoInfo.GITHUB);
    }

    private IssueContext extractIssue(JsonNode payload) {
        var info = repoInfo(payload);
        var issue = payload.path("issue");
        String issueRef = issue.path("number").asText("");
        String title = issue.path("title").asText("");
        String body = issue.path("body").asText("");
        String baseBranch = Naming.resolveBaseBranch(issue.path("ref").asText(""));
        return new IssueContext(info, issueRef, title, body, baseBranch);
    }

    private PrContext extractPr(RepoInfo info, JsonNode pr) {
        return new PrContext(
            info,
            pr.path("number").asInt(),
            pr.path("title").asText(""),
            pr.path("body").asText(""),
            pr.path("merged").asBoolean(false),
            pr.path("head").path("ref").asText(""),
            pr.path("base").path("ref").asText("")
        );
    }

    private PrContext extractPrFromIssue(RepoInfo info, JsonNode issue) {
        return new PrContext(
            info,
            issue.path("number").asInt(),
            issue.path("title").asText(""),
            issue.path("body").asText(""),
            false,
            "",
            ""
        );
    }

    private static boolean isUserAssigned(JsonNode payload, String login) {
        var assignees = payload.path("issue").path("assignees");
        if (assignees.isArray()) {
            for (var a : assignees) {
                if (login.equals(a.path("login").asText(""))) return true;
            }
        }
        return false;
    }

    /**
     * Whether a branch belongs to the agent, per the configured prefix. The
     * adapters used to hardcode "smithy/", which made a provider adapter carry
     * a particular flow.
     */
    private boolean isWorkBranch(String branch) {
        return branch != null && branch.startsWith(branchPrefix);
    }
}
