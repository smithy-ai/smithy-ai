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

    /**
     * URLs attached to the issue as links rather than written into its text.
     *
     * <p>Jira's remote links are where an integration puts what it added — a
     * Figma design linked through the Figma app never appears in the
     * description. Trackers without the concept keep everything in the text,
     * and answer with nothing.
     */
    default List<String> getIssueLinks(String owner, String repo, String issueRef) {
        return List.of();
    }
}
