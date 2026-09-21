package dev.smithyai.orchestrator.service.vcs;

import dev.smithyai.orchestrator.service.vcs.dto.AttachmentInfo;
import dev.smithyai.orchestrator.service.vcs.dto.CommentEntry;
import dev.smithyai.orchestrator.service.vcs.dto.IssueData;
import java.util.List;

public interface IssueTrackerClient extends ProviderClient {
    IssueData getIssue(String owner, String repo, String issueRef);

    List<CommentEntry> getIssueComments(String owner, String repo, String issueRef);

    CommentEntry createIssueComment(String owner, String repo, String issueRef, String body);

    /**
     * Remove one comment. Meant for the bot's own: a planning conversation
     * leaves a trail of superseded plans and acknowledgements that bury the
     * one that was approved, and tidying them is how the issue stays readable.
     */
    default void deleteIssueComment(String owner, String repo, String issueRef, long commentId) {
        throw new UnsupportedOperationException("deleteIssueComment not supported by " + getClass().getSimpleName());
    }

    /**
     * Create an issue. Assignment is deliberately separate — on GitLab,
     * assignee_ids on create silently fail without project membership, so
     * callers create first and then setIssueAssignees.
     */
    default IssueData createIssue(String owner, String repo, String title, String body, List<String> labels) {
        throw new UnsupportedOperationException("createIssue not supported by " + getClass().getSimpleName());
    }

    default void addIssueLabel(String owner, String repo, String issueRef, String label) {
        throw new UnsupportedOperationException("addIssueLabel not supported by " + getClass().getSimpleName());
    }

    void setIssueAssignees(String owner, String repo, String issueRef, List<String> assignees);

    List<AttachmentInfo> getIssueAttachments(String owner, String repo, String issueRef);

    List<AttachmentInfo> getCommentAttachments(String owner, String repo, long commentId);

    byte[] downloadAttachment(String url);
}
