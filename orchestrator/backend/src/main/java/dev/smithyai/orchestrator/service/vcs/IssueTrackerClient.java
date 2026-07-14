package dev.smithyai.orchestrator.service.vcs;

import dev.smithyai.orchestrator.service.vcs.dto.AttachmentInfo;
import dev.smithyai.orchestrator.service.vcs.dto.CommentEntry;
import dev.smithyai.orchestrator.service.vcs.dto.IssueData;
import java.util.List;

public interface IssueTrackerClient {
    IssueData getIssue(String owner, String repo, int number);

    List<CommentEntry> getIssueComments(String owner, String repo, int number);

    CommentEntry createIssueComment(String owner, String repo, int number, String body);

    void setIssueAssignees(String owner, String repo, int number, List<String> assignees);

    List<AttachmentInfo> getIssueAttachments(String owner, String repo, int number);

    List<AttachmentInfo> getCommentAttachments(String owner, String repo, long commentId);

    byte[] downloadAttachment(String url);
}
