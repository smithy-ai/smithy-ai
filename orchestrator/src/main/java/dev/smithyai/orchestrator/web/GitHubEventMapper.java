package dev.smithyai.orchestrator.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.model.*;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.dto.PrData;
import dev.smithyai.orchestrator.workflow.shared.utils.Naming;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GitHubEventMapper {

    private final BotConfig botConfig;
    private final VcsProviderConfig vcsConfig;
    private final VcsClient smithyClient;
    private final String botUser;
    private final String smithyEmail;

    public GitHubEventMapper(BotConfig botConfig, VcsProviderConfig vcsConfig, VcsClient smithyClient) {
        this.botConfig = botConfig;
        this.vcsConfig = vcsConfig;
        this.smithyClient = smithyClient;
        this.botUser = botConfig.resolvedSmithyUser();
        this.smithyEmail = botConfig.resolvedSmithyEmail();
    }

    public WorkflowEvent map(String eventType, JsonNode payload) {
        return switch (eventType) {
            case "issues" -> mapIssuesEvent(payload);
            case "issue_comment" -> mapIssueComment(payload);
            case "push" -> mapPushEvent(payload);
            case "pull_request" -> mapPullRequestEvent(payload);
            case "pull_request_review" -> mapPullRequestReview(payload);
            case "pull_request_review_comment" -> mapPrReviewComment(payload);
            case "workflow_run" -> mapWorkflowRun(payload);
            default -> {
                log.debug("Unhandled GitHub event type: {}", eventType);
                yield null;
            }
        };
    }

    // ── Issues event ─────────────────────────────

    private WorkflowEvent mapIssuesEvent(JsonNode payload) {
        String action = payload.path("action").asText("");
        return switch (action) {
            case "assigned" -> mapIssueAssigned(payload);
            case "unassigned" -> mapIssueUnassigned(payload);
            case "labeled" -> mapIssueLabeled(payload);
            default -> null;
        };
    }

    private WorkflowEvent mapIssueAssigned(JsonNode payload) {
        String assigneeLogin = payload.path("assignee").path("login").asText("");
        if (!botUser.equals(assigneeLogin)) return null;
        if (!"open".equals(payload.path("issue").path("state").asText(""))) return null;

        var info = repoInfo(payload);
        var ctx = extractIssue(info, payload.path("issue"));
        String repoHtmlUrl = payload.path("repository").path("html_url").asText("");
        return new WorkflowEvent.IssueAssigned(ctx, repoHtmlUrl);
    }

    private WorkflowEvent mapIssueUnassigned(JsonNode payload) {
        var issueAssignees = payload.path("issue").path("assignees");
        if (isUserInArray(issueAssignees, botUser)) return null;

        var info = repoInfo(payload);
        var ctx = extractIssue(info, payload.path("issue"));
        return new WorkflowEvent.IssueUnassigned(ctx);
    }

    private WorkflowEvent mapIssueLabeled(JsonNode payload) {
        String labelName = payload.path("label").path("name").asText("");
        if (!"Plan Approved".equals(labelName)) return null;

        var info = repoInfo(payload);
        var ctx = extractIssue(info, payload.path("issue"));
        String approver = payload.path("sender").path("login").asText("");
        return new WorkflowEvent.PlanApproved(ctx, approver);
    }

    // ── Issue comment event ──────────────────────

    private WorkflowEvent mapIssueComment(JsonNode payload) {
        if (!"created".equals(payload.path("action").asText(""))) return null;
        String commentUser = payload.path("comment").path("user").path("login").asText("");
        if (botUser.equals(commentUser)) return null;

        var issue = payload.path("issue");

        // PR conversation comments arrive as issue_comment events
        var pullRequest = issue.path("pull_request");
        if (!pullRequest.isMissingNode() && !pullRequest.isNull()) {
            return mapPrConversationFromIssueComment(payload, commentUser);
        }

        var info = repoInfo(payload);
        var ctx = extractIssue(info, issue);
        String commentBody = payload.path("comment").path("body").asText("");
        return new WorkflowEvent.IssueComment(ctx, commentBody);
    }

    private WorkflowEvent mapPrConversationFromIssueComment(JsonNode payload, String commentUser) {
        var info = repoInfo(payload);
        int prNumber = payload.path("issue").path("number").asInt();
        String commentBody = payload.path("comment").path("body").asText("");

        // Context repo: route to architect
        String repoFull = payload.path("repository").path("full_name").asText("");
        if (repoFull.endsWith("-context") && !commentUser.equals(botConfig.resolvedArchitectUser())) {
            var prc = extractPr(info, payload.path("issue"));
            return new WorkflowEvent.PrConversationComment(prc, commentUser, commentBody);
        }

        // Smithy: needs head branch from API to determine if smithy branch
        try {
            log.debug("Fetching PR #{} from {}/{}", prNumber, info.owner(), info.repo());
            PrData pr = smithyClient.getPullRequest(info.owner(), info.repo(), prNumber);
            String headBranch = pr.headRef();

            if (Naming.isSmithyBranch(headBranch)) {
                Integer issueId = Naming.parseIssueIdFromBranch(headBranch);
                if (issueId != null) {
                    var prc = new PrContext(
                        info,
                        prNumber,
                        pr.title(),
                        pr.body(),
                        pr.merged(),
                        headBranch,
                        pr.baseRef()
                    );
                    return new WorkflowEvent.PrConversationComment(prc, commentUser, commentBody);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch PR #{} for conversation comment routing", prNumber, e);
        }

        return null;
    }

    // ── Push event ───────────────────────────────

    private WorkflowEvent mapPushEvent(JsonNode payload) {
        String ref = payload.path("ref").asText("");
        String branch = ref.replaceFirst("^refs/heads/", "");
        if (!Naming.isSmithyBranch(branch)) return null;

        var commits = payload.path("commits");
        boolean isHuman = false;
        if (commits.isArray()) {
            for (var c : commits) {
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

    // ── Pull request event ───────────────────────

    private WorkflowEvent mapPullRequestEvent(JsonNode payload) {
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

    private WorkflowEvent mapPrReadyForReview(JsonNode payload) {
        var pr = payload.path("pull_request");
        String headBranch = pr.path("head").path("ref").asText("");
        if (!Naming.isSmithyBranch(headBranch)) return null;

        var info = repoInfo(payload);
        var prc = extractPr(info, pr);
        return new WorkflowEvent.PrFinalized(prc);
    }

    private WorkflowEvent mapPrClosed(JsonNode payload) {
        var pr = payload.path("pull_request");
        boolean merged = pr.path("merged").asBoolean(false);
        var info = repoInfo(payload);

        // Merged non-context-repo PRs → PrMerged
        if (merged && !info.repo().endsWith("-context")) {
            var prc = extractPr(info, pr);
            return new WorkflowEvent.PrMerged(prc);
        }

        // Everything else → PrClosed
        String headBranch = pr.path("head").path("ref").asText("");
        int prNumber = pr.path("number").asInt();
        return new WorkflowEvent.PrClosed(info, prNumber, merged, headBranch);
    }

    private WorkflowEvent mapPrUnassigned(JsonNode payload) {
        var pr = payload.path("pull_request");
        String headBranch = pr.path("head").path("ref").asText("");
        if (!Naming.isSmithyBranch(headBranch)) return null;

        Integer issueId = Naming.parseIssueIdFromBranch(headBranch);
        if (issueId == null) return null;

        var assignees = pr.path("assignees");
        if (isUserInArray(assignees, botUser)) return null;

        var info = repoInfo(payload);
        var prc = extractPr(info, pr);
        return new WorkflowEvent.PrUnassigned(prc);
    }

    // ── Pull request review event ────────────────

    private WorkflowEvent mapPullRequestReview(JsonNode payload) {
        if (!"submitted".equals(payload.path("action").asText(""))) return null;

        var review = payload.path("review");
        String reviewUser = review.path("user").path("login").asText("");
        if (botUser.equals(reviewUser)) return null;

        var pr = payload.path("pull_request");
        String headBranch = pr.path("head").path("ref").asText("");
        if (!Naming.isSmithyBranch(headBranch)) return null;

        Integer issueId = Naming.parseIssueIdFromBranch(headBranch);
        if (issueId == null) return null;

        long reviewId = review.path("id").asLong();
        String reviewBody = review.path("body").asText("");

        var info = repoInfo(payload);
        var prc = extractPr(info, pr);
        return new WorkflowEvent.ReviewSubmitted(prc, reviewId, reviewBody, reviewUser);
    }

    // ── Pull request review comment event ────────

    private WorkflowEvent mapPrReviewComment(JsonNode payload) {
        if (!"created".equals(payload.path("action").asText(""))) return null;

        var comment = payload.path("comment");
        String commentUser = comment.path("user").path("login").asText("");
        if (botUser.equals(commentUser)) return null;

        var pr = payload.path("pull_request");
        String headBranch = pr.path("head").path("ref").asText("");
        var info = repoInfo(payload);

        // Context repo PR comments → route to architect
        String repoFull = payload.path("repository").path("full_name").asText("");
        if (repoFull.endsWith("-context") && !commentUser.equals(botConfig.resolvedArchitectUser())) {
            var prc = extractPr(info, pr);
            String commentBody = comment.path("body").asText("");
            return new WorkflowEvent.PrConversationComment(prc, commentUser, commentBody);
        }

        // Smithy: review comment on smithy branch PR
        if (Naming.isSmithyBranch(headBranch)) {
            Integer issueId = Naming.parseIssueIdFromBranch(headBranch);
            if (issueId != null) {
                var prc = extractPr(info, pr);
                var cd = new CommentData(
                    commentUser,
                    comment.path("body").asText(""),
                    comment.path("path").asText(""),
                    comment.path("position").asInt(0)
                );
                return new WorkflowEvent.PrReviewComment(prc, List.of(cd));
            }
        }

        return null;
    }

    // ── Workflow run event ───────────────────────

    private WorkflowEvent mapWorkflowRun(JsonNode payload) {
        if (!"completed".equals(payload.path("action").asText(""))) return null;

        var run = payload.path("workflow_run");
        String headBranch = run.path("head_branch").asText("");
        String conclusion = run.path("conclusion").asText("");

        if (!"failure".equals(conclusion) && !"success".equals(conclusion)) return null;

        var info = repoInfo(payload);

        // Try to find PR number from workflow_run.pull_requests[0].number
        Integer prNumber = null;
        var prs = run.path("pull_requests");
        if (prs.isArray() && !prs.isEmpty()) {
            prNumber = prs.get(0).path("number").asInt();
            if (prNumber == 0) prNumber = null;
        }

        // Fallback to findPrByHead
        if (prNumber == null && !headBranch.isBlank()) {
            try {
                PrData pr = smithyClient.findPrByHead(info.owner(), info.repo(), headBranch);
                if (pr != null) {
                    prNumber = pr.number();
                }
            } catch (Exception e) {
                log.warn("Failed to find PR for branch {} in workflow_run event", headBranch, e);
            }
        }

        if (!Naming.isSmithyBranch(headBranch)) {
            log.info("Workflow run on non-smithy branch {}, ignoring", headBranch);
            return null;
        }

        var ciRun = new CiRunInfo(headBranch, prNumber);
        String workflowName = run.path("name").asText("");

        if ("failure".equals(conclusion)) {
            return new WorkflowEvent.CiFailure(info, ciRun, workflowName);
        } else {
            return new WorkflowEvent.CiRecovery(info, ciRun);
        }
    }

    // ── Extraction helpers ───────────────────────

    private RepoInfo repoInfo(JsonNode payload) {
        String effectiveUrl = vcsConfig.github() != null
            && vcsConfig.github().url() != null
            && !vcsConfig.github().url().isBlank()
            ? vcsConfig.github().url()
            : "https://github.com";
        return Naming.repoInfo(payload, effectiveUrl);
    }

    private IssueContext extractIssue(RepoInfo info, JsonNode issue) {
        int number = issue.path("number").asInt();
        String title = issue.path("title").asText("");
        String body = issue.path("body").asText("");
        String baseBranch = Naming.resolveBaseBranch(issue.path("ref").asText(""));
        return new IssueContext(info, number, title, body, baseBranch);
    }

    private PrContext extractPr(RepoInfo info, JsonNode pr) {
        return new PrContext(
            info,
            pr.path("number").asInt(),
            pr.path("title").asText(""),
            pr.path("body").asText(""),
            pr.path("merged").asBoolean(false),
            pr.path("head").path("ref").asText("main"),
            pr.path("base").path("ref").asText("main")
        );
    }

    private static boolean isUserInArray(JsonNode array, String login) {
        if (array == null || !array.isArray()) return false;
        for (var item : array) {
            if (login.equals(item.path("login").asText(""))) return true;
        }
        return false;
    }
}
