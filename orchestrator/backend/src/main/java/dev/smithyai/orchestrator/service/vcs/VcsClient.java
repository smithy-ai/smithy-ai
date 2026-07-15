package dev.smithyai.orchestrator.service.vcs;

import dev.smithyai.orchestrator.service.vcs.dto.*;
import java.util.List;

public interface VcsClient {
    // Pull/Merge Requests
    PrData createPullRequest(
        String owner,
        String repo,
        String title,
        String head,
        String base,
        String body,
        boolean draft
    );

    PrData getPullRequest(String owner, String repo, int number);

    PrData findPrByHead(String owner, String repo, String head);

    void createPrComment(String owner, String repo, int prNumber, String body);

    // Reviews
    void createPullReview(
        String owner,
        String repo,
        int prNumber,
        String body,
        String event,
        List<InlineComment> comments
    );

    List<CommentEntry> getPrComments(String owner, String repo, int prNumber);

    List<ReviewEntry> getPrReviews(String owner, String repo, int prNumber);

    List<ReviewCommentEntry> getReviewComments(String owner, String repo, int prNumber, long reviewId);

    LatestReviewResult getLatestReviewComments(String owner, String repo, int prNumber, String reviewer);

    // PR assignees & reviewers
    void setPrAssignees(String owner, String repo, int prNumber, List<String> assignees);

    void requestReview(String owner, String repo, int prNumber, List<String> reviewers);

    boolean isAssigned(String owner, String repo, int prNumber, String username);

    // Repository
    boolean repoExists(String owner, String repo);

    // URL helpers (provider-specific URL patterns)
    String fileBrowseUrl(String repoHtmlUrl, String branch, String path);

    String prUrl(String externalBaseUrl, String owner, String repo, int number);

    String cloneUrl(String owner, String repo);

    String baseUrl();
}
