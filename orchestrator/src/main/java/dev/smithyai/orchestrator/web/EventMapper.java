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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class EventMapper {

    private static final String SMITHY_EMAIL = "smithy@localhost";

    private final BotConfig botConfig;
    private final VcsProviderConfig vcsConfig;
    private final VcsClient smithyClient;
    private final String botUser;

    public EventMapper(
        BotConfig botConfig,
        VcsProviderConfig vcsConfig,
        @Qualifier("smithyVcs") VcsClient smithyClient
    ) {
        this.botConfig = botConfig;
        this.vcsConfig = vcsConfig;
        this.smithyClient = smithyClient;
        this.botUser = botConfig.resolvedSmithyUser();
    }

    // ── Issue events ─────────────────────────────

    public WorkflowEvent mapIssueEvent(String action, JsonNode payload) {
        return switch (action) {
            case "assigned" -> mapIssueAssigned(payload);
            case "unassigned" -> mapIssueUnassigned(payload);
            case "label", "label_updated" -> mapIssueLabel(payload);
            default -> null;
        };
    }

    private WorkflowEvent mapIssueAssigned(JsonNode payload) {
        if (!isUserAssigned(payload, botUser)) return null;
        if (!"open".equals(payload.path("issue").path("state").asText(""))) return null;

        var ctx = extractIssue(payload);
        String repoHtmlUrl = payload.get("repository").get("html_url").asText("");
        return new WorkflowEvent.IssueAssigned(ctx, repoHtmlUrl);
    }

    private WorkflowEvent mapIssueUnassigned(JsonNode payload) {
        if (isUserAssigned(payload, botUser)) return null;

        var ctx = extractIssue(payload);
        return new WorkflowEvent.IssueUnassigned(ctx);
    }

    private WorkflowEvent mapIssueLabel(JsonNode payload) {
        String labelName = payload.path("label").path("name").asText("");
        if (labelName.isBlank()) {
            var issueLabels = payload.path("issue").path("labels");
            if (issueLabels.isArray()) {
                for (var l : issueLabels) {
                    if ("Plan Approved".equals(l.path("name").asText(""))) {
                        labelName = "Plan Approved";
                        break;
                    }
                }
            }
        }
        if (!"Plan Approved".equals(labelName)) return null;

        String approver = payload.path("sender").path("login").asText("");
        var ctx = extractIssue(payload);
        return new WorkflowEvent.PlanApproved(ctx, approver);
    }

    // ── Issue comment events ─────────────────────

    public WorkflowEvent mapIssueComment(JsonNode payload) {
        if (!"created".equals(payload.path("action").asText(""))) return null;
        String commentUser = payload.path("comment").path("user").path("login").asText("");
        if (botUser.equals(commentUser)) return null;

        var issue = payload.get("issue");

        // PR conversation comments arrive as issue_comment events
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

    // ── Push events ──────────────────────────────

    public WorkflowEvent mapPush(JsonNode payload) {
        String ref = payload.path("ref").asText("");
        String branch = ref.replaceFirst("^refs/heads/", "");
        if (!Naming.isSmithyBranch(branch)) return null;

        var commits = payload.get("commits");
        boolean isHuman = false;
        if (commits != null && commits.isArray()) {
            for (var c : commits) {
                if (!SMITHY_EMAIL.equals(c.path("committer").path("email").asText(""))) {
                    isHuman = true;
                    break;
                }
            }
        }
        if (!isHuman) return null;

        var info = repoInfo(payload);
        return new WorkflowEvent.HumanPush(info, branch);
    }

    // ── Pull request events ──────────────────────

    public WorkflowEvent mapPullRequest(String action, JsonNode payload) {
        return switch (action) {
            case "review_requested" -> mapReviewRequested(payload);
            case "edited" -> mapPrEdited(payload);
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

    private WorkflowEvent mapPrEdited(JsonNode payload) {
        var pr = payload.path("pull_request");
        String oldTitle = payload.path("changes").path("title").path("from").asText("");
        String newTitle = pr.path("title").asText("");
        if (!oldTitle.startsWith("WIP:") || newTitle.startsWith("WIP:")) return null;

        var info = repoInfo(payload);
        String headBranch = pr.path("head").path("ref").asText("");
        Integer issueId = Naming.parseIssueIdFromBranch(headBranch);
        if (issueId == null) return null;

        var prc = extractPr(info, pr);
        return new WorkflowEvent.PrFinalized(prc);
    }

    private WorkflowEvent mapPrClosed(JsonNode payload) {
        var pr = payload.path("pull_request");
        boolean merged = pr.path("merged").asBoolean(false);
        var info = repoInfo(payload);

        // Merged non-context-repo PRs → PrMerged (architect learns from these)
        if (merged && !info.repo().endsWith("-context")) {
            var prc = extractPr(info, pr);
            return new WorkflowEvent.PrMerged(prc);
        }

        // Everything else → PrClosed (architect uses for context-repo cleanup)
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
        boolean smithyAssigned = false;
        if (assignees.isArray()) {
            for (var a : assignees) {
                if (botUser.equals(a.path("login").asText(""))) {
                    smithyAssigned = true;
                    break;
                }
            }
        }
        if (smithyAssigned) return null;

        var info = repoInfo(payload);
        var prc = extractPr(info, pr);
        return new WorkflowEvent.PrUnassigned(prc);
    }

    // ── PR comment events ────────────────────────

    public WorkflowEvent mapPrComment(JsonNode payload) {
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
            var cd = commentFromPayload(payload);
            return new WorkflowEvent.PrConversationComment(prc, commentUser, cd.body());
        }

        // Smithy: review comment on smithy branch PR
        if (Naming.isSmithyBranch(headBranch)) {
            Integer issueId = Naming.parseIssueIdFromBranch(headBranch);
            if (issueId != null) {
                var prc = extractPr(info, pr);
                var cd = commentFromPayload(payload);
                return new WorkflowEvent.PrReviewComment(prc, List.of(cd));
            }
        }

        return null;
    }

    // ── Review submitted events ──────────────────

    public WorkflowEvent mapReviewSubmitted(JsonNode payload) {
        if (!"reviewed".equals(payload.path("action").asText(""))) return null;

        var review = payload.path("review");
        String reviewUser = review.path("user").path("login").asText(payload.path("sender").path("login").asText(""));
        if (botUser.equals(reviewUser)) return null;

        var pr = payload.path("pull_request");
        String headBranch = pr.path("head").path("ref").asText("");
        var info = repoInfo(payload);

        if (!Naming.isSmithyBranch(headBranch)) return null;
        Integer issueId = Naming.parseIssueIdFromBranch(headBranch);
        if (issueId == null) return null;

        long reviewId = review.path("id").asLong();
        String reviewBody = review.path("body").asText("");

        var prc = extractPr(info, pr);
        return new WorkflowEvent.ReviewSubmitted(prc, reviewId, reviewBody, reviewUser);
    }

    // ── CI events ────────────────────────────────

    public WorkflowEvent mapCiEvent(String eventType, JsonNode payload) {
        var resolved = resolveCiRun(payload);
        if (resolved == null) return null;

        var info = resolved.info();
        var ciRun = resolved.ciRun();

        if ("action_run_failure".equals(eventType)) {
            if (!Naming.isSmithyBranch(ciRun.headBranch())) {
                log.info("CI failure on non-smithy branch {}, ignoring", ciRun.headBranch());
                return null;
            }
            String workflowName = payload.path("run").path("name").asText("");
            return new WorkflowEvent.CiFailure(info, ciRun, workflowName);
        } else if ("action_run_recover".equals(eventType)) {
            return new WorkflowEvent.CiRecovery(info, ciRun);
        }

        return null;
    }

    // ── Shared extraction helpers ────────────────

    private RepoInfo repoInfo(JsonNode payload) {
        return Naming.repoInfo(payload, vcsConfig.resolvedUrl());
    }

    private IssueContext extractIssue(JsonNode payload) {
        var info = repoInfo(payload);
        var issue = payload.get("issue");
        int number = issue.get("number").asInt();
        String title = issue.get("title").asText();
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

    private record ResolvedCiRun(RepoInfo info, CiRunInfo ciRun) {}

    private ResolvedCiRun resolveCiRun(JsonNode payload) {
        var run = payload.path("run");
        String prettyref = run.path("prettyref").asText("");
        if (prettyref.isBlank()) return null;

        var runRepo = run.path("repository");
        if (!runRepo.has("full_name")) return null;
        String[] parts = runRepo.get("full_name").asText("").split("/", 2);
        if (parts.length < 2) return null;
        String owner = parts[0];
        String repoName = parts[1];

        String headBranch;
        Integer prNumber;

        if (prettyref.startsWith("#")) {
            prNumber = Integer.parseInt(prettyref.substring(1));
            PrData pr = smithyClient.getPullRequest(owner, repoName, prNumber);
            headBranch = pr.headRef();
        } else {
            headBranch = prettyref;
            PrData pr = smithyClient.findPrByHead(owner, repoName, headBranch);
            prNumber = pr != null ? pr.number() : null;
        }

        var info = new RepoInfo(owner, repoName, "");
        return new ResolvedCiRun(info, new CiRunInfo(headBranch, prNumber));
    }

    private static boolean isUserAssigned(JsonNode payload, String login) {
        var assignees = payload.path("issue").path("assignees");
        if (assignees.isArray()) {
            for (var a : assignees) {
                if (login.equals(a.path("login").asText(""))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static CommentData commentFromPayload(JsonNode payload) {
        var comment = payload.path("comment");
        return new CommentData(
            comment.path("user").path("login").asText(""),
            comment.path("body").asText(""),
            comment.path("path").asText(""),
            comment.path("line").asInt(0)
        );
    }
}
