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
public class GitLabEventMapper {

    private final BotConfig botConfig;
    private final VcsProviderConfig vcsConfig;
    private final VcsClient smithyClient;
    private final String botUser;
    private final String smithyEmail;

    public GitLabEventMapper(BotConfig botConfig, VcsProviderConfig vcsConfig, VcsClient smithyClient) {
        this.botConfig = botConfig;
        this.vcsConfig = vcsConfig;
        this.smithyClient = smithyClient;
        this.botUser = botConfig.resolvedSmithyUser();
        this.smithyEmail = botConfig.resolvedSmithyEmail();
    }

    public WorkflowEvent map(String eventType, JsonNode payload) {
        return switch (eventType) {
            case "Issue Hook" -> mapIssueHook(payload);
            case "Note Hook" -> mapNoteHook(payload);
            case "Push Hook" -> mapPushHook(payload);
            case "Merge Request Hook" -> mapMergeRequestHook(payload);
            case "Pipeline Hook" -> mapPipelineHook(payload);
            default -> {
                log.debug("Unhandled GitLab event type: {}", eventType);
                yield null;
            }
        };
    }

    // ── Issue Hook ──────────────────────────────

    private WorkflowEvent mapIssueHook(JsonNode payload) {
        var attrs = payload.path("object_attributes");
        String action = attrs.path("action").asText("");

        return switch (action) {
            case "open" -> mapIssueOpen(payload, attrs);
            case "update" -> mapIssueUpdate(payload, attrs);
            default -> null;
        };
    }

    private WorkflowEvent mapIssueOpen(JsonNode payload, JsonNode attrs) {
        var assignees = payload.path("assignees");
        if (!isUserInArray(assignees, botUser)) return null;

        var info = repoInfo(payload);
        var ctx = extractIssueFromAttrs(info, attrs);
        String repoHtmlUrl = payload.path("project").path("web_url").asText("");
        return new WorkflowEvent.IssueAssigned(ctx, repoHtmlUrl);
    }

    private WorkflowEvent mapIssueUpdate(JsonNode payload, JsonNode attrs) {
        var changes = payload.path("changes");

        // Assignee changes
        if (changes.has("assignees")) {
            return mapIssueAssigneeChange(payload, attrs, changes);
        }

        // Label changes
        if (changes.has("labels")) {
            return mapIssueLabelChange(payload, attrs);
        }

        return null;
    }

    private WorkflowEvent mapIssueAssigneeChange(JsonNode payload, JsonNode attrs, JsonNode changes) {
        var info = repoInfo(payload);
        var ctx = extractIssueFromAttrs(info, attrs);

        var currentAssignees = payload.path("assignees");
        boolean currentlyAssigned = isUserInArray(currentAssignees, botUser);

        var previousAssignees = changes.path("assignees").path("previous");
        boolean previouslyAssigned = isUserInArray(previousAssignees, botUser);

        if (currentlyAssigned && !previouslyAssigned) {
            if (!"opened".equals(attrs.path("state").asText(""))) return null;
            String repoHtmlUrl = payload.path("project").path("web_url").asText("");
            return new WorkflowEvent.IssueAssigned(ctx, repoHtmlUrl);
        } else if (!currentlyAssigned && previouslyAssigned) {
            return new WorkflowEvent.IssueUnassigned(ctx);
        }

        return null;
    }

    private WorkflowEvent mapIssueLabelChange(JsonNode payload, JsonNode attrs) {
        var labels = attrs.path("labels");
        boolean hasPlanApproved = false;
        if (labels.isArray()) {
            for (var l : labels) {
                if ("Plan Approved".equals(l.path("title").asText(""))) {
                    hasPlanApproved = true;
                    break;
                }
            }
        }
        if (!hasPlanApproved) return null;

        var info = repoInfo(payload);
        var ctx = extractIssueFromAttrs(info, attrs);
        String approver = payload.path("user").path("username").asText("");
        return new WorkflowEvent.PlanApproved(ctx, approver);
    }

    // ── Note Hook ───────────────────────────────

    private WorkflowEvent mapNoteHook(JsonNode payload) {
        var attrs = payload.path("object_attributes");
        String noteableType = attrs.path("noteable_type").asText("");
        String commentUser = attrs
            .path("author")
            .path("username")
            .asText(payload.path("user").path("username").asText(""));
        if (botUser.equals(commentUser)) return null;

        return switch (noteableType) {
            case "Issue" -> mapIssueNote(payload, attrs, commentUser);
            case "MergeRequest" -> mapMrNote(payload, attrs, commentUser);
            default -> null;
        };
    }

    private WorkflowEvent mapIssueNote(JsonNode payload, JsonNode attrs, String commentUser) {
        var info = repoInfo(payload);
        var issue = payload.path("issue");
        var ctx = extractIssueFromAttrs(info, issue);
        String commentBody = attrs.path("note").asText("");
        return new WorkflowEvent.IssueComment(ctx, commentBody);
    }

    private WorkflowEvent mapMrNote(JsonNode payload, JsonNode attrs, String commentUser) {
        var info = repoInfo(payload);
        var mr = payload.path("merge_request");
        var prc = extractPrFromMr(info, mr);

        String commentBody = attrs.path("note").asText("");
        String type = attrs.path("type").asText("");

        // DiffNote → review comment
        if ("DiffNote".equals(type)) {
            var position = attrs.path("position");
            String path = position.path("new_path").asText("");
            int line = position.path("new_line").asInt(0);
            var cd = new CommentData(commentUser, commentBody, path, line);
            return new WorkflowEvent.PrReviewComment(prc, List.of(cd));
        }

        // Regular note → conversation comment
        String repoFull = payload.path("project").path("path_with_namespace").asText("");
        if (repoFull.endsWith("-context") && !commentUser.equals(botConfig.resolvedArchitectUser())) {
            return new WorkflowEvent.PrConversationComment(prc, commentUser, commentBody);
        }

        String headBranch = mr.path("source_branch").asText("");
        if (Naming.isSmithyBranch(headBranch)) {
            return new WorkflowEvent.PrConversationComment(prc, commentUser, commentBody);
        }

        return null;
    }

    // ── Push Hook ───────────────────────────────

    private WorkflowEvent mapPushHook(JsonNode payload) {
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

    // ── Merge Request Hook ──────────────────────

    private WorkflowEvent mapMergeRequestHook(JsonNode payload) {
        var attrs = payload.path("object_attributes");
        String action = attrs.path("action").asText("");

        return switch (action) {
            case "update" -> mapMrUpdate(payload, attrs);
            case "close" -> mapMrClose(payload, attrs);
            case "merge" -> mapMrMerge(payload, attrs);
            case "approved" -> mapMrApproved(payload, attrs);
            default -> null;
        };
    }

    private WorkflowEvent mapMrUpdate(JsonNode payload, JsonNode attrs) {
        var changes = payload.path("changes");
        var info = repoInfo(payload);

        // Reviewer added
        if (changes.has("reviewers")) {
            var currentReviewers = payload.path("reviewers");
            if (currentReviewers.isArray()) {
                for (var r : currentReviewers) {
                    if (botConfig.resolvedArchitectUser().equals(r.path("username").asText(""))) {
                        var prc = extractPrFromMr(info, attrs);
                        return new WorkflowEvent.ReviewRequested(prc);
                    }
                }
            }
        }

        // Draft → ready (title change)
        if (changes.has("draft")) {
            boolean wasDraft = changes.path("draft").path("previous").asBoolean(true);
            boolean isDraft = attrs.path("draft").asBoolean(false);
            if (wasDraft && !isDraft) {
                var prc = extractPrFromMr(info, attrs);
                return new WorkflowEvent.PrFinalized(prc);
            }
        }

        // Unassigned — only fire if bot was previously assigned
        if (changes.has("assignees")) {
            String headBranch = attrs.path("source_branch").asText("");
            if (Naming.isSmithyBranch(headBranch)) {
                var previousAssignees = changes.path("assignees").path("previous");
                boolean previouslyAssigned = isUserInArray(previousAssignees, botUser);
                var currentAssignees = attrs.path("assignees");
                if (!isUserInArray(currentAssignees, botUser) && previouslyAssigned) {
                    var prc = extractPrFromMr(info, attrs);
                    return new WorkflowEvent.PrUnassigned(prc);
                }
            }
        }

        return null;
    }

    private WorkflowEvent mapMrClose(JsonNode payload, JsonNode attrs) {
        var info = repoInfo(payload);
        String headBranch = attrs.path("source_branch").asText("");
        int mrNumber = attrs.path("iid").asInt();
        return new WorkflowEvent.PrClosed(info, mrNumber, false, headBranch);
    }

    private WorkflowEvent mapMrMerge(JsonNode payload, JsonNode attrs) {
        var info = repoInfo(payload);
        if (!info.repo().endsWith("-context")) {
            var prc = extractPrFromMr(info, attrs);
            return new WorkflowEvent.PrMerged(prc);
        }
        String headBranch = attrs.path("source_branch").asText("");
        int mrNumber = attrs.path("iid").asInt();
        return new WorkflowEvent.PrClosed(info, mrNumber, true, headBranch);
    }

    private WorkflowEvent mapMrApproved(JsonNode payload, JsonNode attrs) {
        String reviewer = payload.path("user").path("username").asText("");
        if (botUser.equals(reviewer)) return null;

        var info = repoInfo(payload);
        String headBranch = attrs.path("source_branch").asText("");
        if (!Naming.isSmithyBranch(headBranch)) return null;

        var prc = extractPrFromMr(info, attrs);
        return new WorkflowEvent.ReviewSubmitted(prc, 0, "", reviewer);
    }

    // ── Pipeline Hook ───────────────────────────

    private WorkflowEvent mapPipelineHook(JsonNode payload) {
        var attrs = payload.path("object_attributes");
        String status = attrs.path("status").asText("");
        String ref = attrs.path("ref").asText("");

        if (!"failed".equals(status) && !"success".equals(status)) return null;

        var info = repoInfo(payload);

        // Try to find associated MR
        var mrNode = payload.path("merge_request");
        Integer prNumber = null;
        String headBranch = ref;

        if (!mrNode.isMissingNode() && !mrNode.isNull()) {
            prNumber = mrNode.path("iid").asInt();
            headBranch = mrNode.path("source_branch").asText(ref);
        } else {
            // Try to find MR by branch
            try {
                PrData pr = smithyClient.findPrByHead(info.owner(), info.repo(), ref);
                if (pr != null) {
                    prNumber = pr.number();
                    headBranch = pr.headRef();
                }
            } catch (Exception e) {
                log.warn("Failed to find MR for branch {} in pipeline hook", ref, e);
            }
        }

        var ciRun = new CiRunInfo(headBranch, prNumber);

        if ("failed".equals(status)) {
            if (!Naming.isSmithyBranch(headBranch)) {
                log.info("CI failure on non-smithy branch {}, ignoring", headBranch);
                return null;
            }
            String pipelineName = attrs.path("name").asText("");
            return new WorkflowEvent.CiFailure(info, ciRun, pipelineName);
        } else {
            return new WorkflowEvent.CiRecovery(info, ciRun);
        }
    }

    // ── Extraction helpers ──────────────────────

    private RepoInfo repoInfo(JsonNode payload) {
        var project = payload.path("project");
        String pathWithNamespace = project.path("path_with_namespace").asText("");
        String[] parts = pathWithNamespace.split("/", 2);
        if (parts.length < 2) {
            parts = new String[] { "", pathWithNamespace };
        }
        String gitUrl = project.path("git_http_url").asText("");
        // Rewrite to internal URL if needed
        String gitlabUrl = vcsConfig.gitlab() != null ? vcsConfig.gitlab().url() : null;
        if (gitlabUrl != null && !gitlabUrl.isBlank() && !gitUrl.isBlank()) {
            try {
                var publicUri = java.net.URI.create(gitUrl);
                var internalUri = java.net.URI.create(gitlabUrl);
                gitUrl = gitUrl.replaceFirst(
                    java.util.regex.Pattern.quote(publicUri.getScheme() + "://" + publicUri.getAuthority()),
                    internalUri.getScheme() + "://" + internalUri.getAuthority()
                );
            } catch (Exception e) {
                log.warn("Failed to rewrite GitLab URL: {}", gitUrl);
            }
        }
        return new RepoInfo(parts[0], parts[1], gitUrl);
    }

    private IssueContext extractIssueFromAttrs(RepoInfo info, JsonNode attrs) {
        int number = attrs.path("iid").asInt(attrs.path("number").asInt());
        String title = attrs.path("title").asText("");
        String body = attrs.path("description").asText("");
        // GitLab doesn't have a direct "ref" field on issues — default to main
        String baseBranch = Naming.resolveBaseBranch("");
        return new IssueContext(info, number, title, body, baseBranch);
    }

    private PrContext extractPrFromMr(RepoInfo info, JsonNode mr) {
        return new PrContext(
            info,
            mr.path("iid").asInt(),
            mr.path("title").asText(""),
            mr.path("description").asText(""),
            "merged".equals(mr.path("state").asText("")),
            mr.path("source_branch").asText("main"),
            mr.path("target_branch").asText("main")
        );
    }

    private static boolean isUserInArray(JsonNode array, String username) {
        if (array == null || !array.isArray()) return false;
        for (var item : array) {
            if (username.equals(item.path("username").asText(""))) return true;
        }
        return false;
    }
}
