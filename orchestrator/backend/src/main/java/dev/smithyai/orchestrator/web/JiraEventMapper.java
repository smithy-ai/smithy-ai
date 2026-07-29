package dev.smithyai.orchestrator.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig.JiraProviderConfig;
import dev.smithyai.orchestrator.model.IssueContext;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;

/**
 * Maps Jira webhook payloads (jira:issue_created, jira:issue_updated,
 * comment_created) to workflow events. Jira stories carry no repository, so
 * the target repo is read from a configured custom field holding
 * "owner/repo" or "owner/repo@base-branch"; assignments without it get a
 * comment asking for the field and are otherwise ignored.
 */
@Slf4j
public class JiraEventMapper {

    private final JiraProviderConfig jira;
    private final VcsClient smithyVcs;
    private final IssueTrackerClient issueTracker;
    private final boolean foremanEnabled;

    public JiraEventMapper(
        VcsProviderConfig vcsConfig,
        VcsClient smithyVcs,
        IssueTrackerClient issueTracker,
        boolean foremanEnabled
    ) {
        this.jira = vcsConfig.jira();
        this.smithyVcs = smithyVcs;
        this.issueTracker = issueTracker;
        this.foremanEnabled = foremanEnabled;
        log.info(
            "JiraEventMapper initialized: botAccountId='{}', repoField='{}', approvalLabel='{}'",
            jira.botAccountId(),
            jira.repoField(),
            jira.resolvedPlanApprovedLabel()
        );
    }

    public WorkflowEvent map(JsonNode payload) {
        String webhookEvent = payload.path("webhookEvent").asText("");
        return switch (webhookEvent) {
            case "jira:issue_created" -> mapIssueCreated(payload);
            case "jira:issue_updated" -> mapIssueUpdated(payload);
            case "comment_created" -> mapCommentCreated(payload);
            default -> {
                log.debug("Unhandled Jira webhook event: {}", webhookEvent);
                yield null;
            }
        };
    }

    // ── Issue events ─────────────────────────────────────────

    private WorkflowEvent mapIssueCreated(JsonNode payload) {
        var issue = payload.path("issue");
        if (!isBot(issue.path("fields").path("assignee"))) return null;
        return issueAssigned(payload, issue);
    }

    private WorkflowEvent mapIssueUpdated(JsonNode payload) {
        var issue = payload.path("issue");
        var items = payload.path("changelog").path("items");
        if (!items.isArray()) return null;

        for (var item : items) {
            String field = item.path("field").asText("");
            switch (field) {
                case "assignee" -> {
                    String bot = jira.botAccountId();
                    boolean wasBot = bot.equals(item.path("from").asText(""));
                    boolean isBotNow = bot.equals(item.path("to").asText(""));
                    if (isBotNow && !wasBot) {
                        return issueAssigned(payload, issue);
                    }
                    if (wasBot && !isBotNow) {
                        var ctx = extractIssue(issue, false);
                        return ctx != null ? new WorkflowEvent.IssueUnassigned(ctx) : null;
                    }
                }
                case "labels" -> {
                    String label = jira.resolvedPlanApprovedLabel();
                    if (
                        containsLabel(item.path("toString").asText(""), label) &&
                        !containsLabel(item.path("fromString").asText(""), label)
                    ) {
                        return planApproved(payload, issue);
                    }
                }
                case "status" -> {
                    String approvedStatus = jira.planApprovedStatus();
                    if (
                        approvedStatus != null &&
                        !approvedStatus.isBlank() &&
                        approvedStatus.equalsIgnoreCase(item.path("toString").asText(""))
                    ) {
                        return planApproved(payload, issue);
                    }
                }
                default -> {}
            }
        }
        return null;
    }

    private WorkflowEvent mapCommentCreated(JsonNode payload) {
        var issue = payload.path("issue");
        var comment = payload.path("comment");
        String author = comment.path("author").path("accountId").asText(comment.path("author").path("name").asText(""));
        if (jira.botAccountId().equals(author)) return null;
        if (!isBot(issue.path("fields").path("assignee"))) return null;

        var ctx = extractIssue(issue, false);
        if (ctx == null) return null;
        return new WorkflowEvent.IssueComment(ctx, comment.path("body").asText(""));
    }

    // ── Builders ─────────────────────────────────────────────

    private WorkflowEvent issueAssigned(JsonNode payload, JsonNode issue) {
        var ctx = extractIssue(issue, true);
        if (ctx == null) return null;
        // Jira has no repo html url; the plan-link comment uses the VCS external URL
        return new WorkflowEvent.IssueAssigned(ctx, smithyVcs.baseUrl() + "/" + ctx.info().owner() + "/" + ctx.info().repo());
    }

    private WorkflowEvent planApproved(JsonNode payload, JsonNode issue) {
        if (!isBot(issue.path("fields").path("assignee"))) return null;
        var ctx = extractIssue(issue, false);
        if (ctx == null) return null;
        // The Jira actor has no known VCS username — leave approver blank so
        // the workflow skips the review-request step.
        return new WorkflowEvent.PlanApproved(ctx, "");
    }

    /**
     * @param commentOnMissingRepo whether to reply on the story when the repo
     *        custom field is absent (only on assignment, to avoid comment spam)
     */
    private IssueContext extractIssue(JsonNode issue, boolean commentOnMissingRepo) {
        String key = issue.path("key").asText("");
        var fields = issue.path("fields");

        String repoField = jira.repoField();
        String repoValue = repoField != null && !repoField.isBlank() ? fields.path(repoField).asText("") : "";
        if (repoValue.isBlank()) {
            if (foremanEnabled) {
                // The foreman plans the repo set itself; a placeholder RepoInfo
                // carries the story through routing (Jira ignores owner/repo).
                String projectKey = key.contains("-") ? key.substring(0, key.indexOf('-')).toLowerCase() : "jira";
                var info = new RepoInfo(projectKey, "story", "");
                return new IssueContext(
                    info,
                    key,
                    fields.path("summary").asText(""),
                    fields.path("description").asText(""),
                    ""
                );
            }
            log.warn("Jira issue {} has no repository field value ({}), ignoring", key, repoField);
            if (commentOnMissingRepo) {
                try {
                    issueTracker.createIssueComment(
                        "",
                        "",
                        key,
                        "I can't start on this story: the repository field is empty. " +
                        "Set it to `owner/repo` (optionally `owner/repo@base-branch`) and re-assign me."
                    );
                } catch (Exception e) {
                    log.warn("Failed to comment on {} about missing repo field", key, e);
                }
            }
            return null;
        }

        String baseBranch = "";
        String repoPath = repoValue.strip();
        int at = repoPath.indexOf('@');
        if (at > 0) {
            baseBranch = repoPath.substring(at + 1);
            repoPath = repoPath.substring(0, at);
        }
        String[] parts = repoPath.split("/", 2);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            log.warn("Jira issue {} repository field '{}' is not owner/repo, ignoring", key, repoValue);
            return null;
        }

        var info = new RepoInfo(parts[0], parts[1], smithyVcs.cloneUrl(parts[0], parts[1]));
        return new IssueContext(
            info,
            key,
            fields.path("summary").asText(""),
            fields.path("description").asText(""),
            baseBranch
        );
    }

    private boolean isBot(JsonNode assignee) {
        if (assignee.isMissingNode() || assignee.isNull()) return false;
        String id = assignee.path("accountId").asText(assignee.path("name").asText(""));
        return jira.botAccountId().equals(id);
    }

    private static boolean containsLabel(String spaceSeparated, String label) {
        return Arrays.asList(spaceSeparated.split("\\s+")).contains(label);
    }
}
