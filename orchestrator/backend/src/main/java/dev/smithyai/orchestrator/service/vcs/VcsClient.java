package dev.smithyai.orchestrator.service.vcs;

import dev.smithyai.orchestrator.service.vcs.dto.*;
import java.util.List;
import java.util.Optional;

public interface VcsClient extends ProviderClient {
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

    /**
     * React to a PR comment with an emoji (e.g. "eyes" as an acknowledgment).
     * Best-effort: providers without reaction support may leave this a no-op.
     */
    default void reactToPrComment(String owner, String repo, int prNumber, long commentId, String reaction) {}

    /**
     * Reply inside the discussion thread of the comment being answered.
     * Providers without threaded discussions fall back to a top-level comment.
     */
    default void replyToPrDiscussion(String owner, String repo, int prNumber, String discussionId, String body) {
        createPrComment(owner, repo, prNumber, body);
    }

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

    /**
     * Read a file's raw content from a branch, or null if it doesn't exist.
     */
    default String getRawFile(String owner, String repo, String branch, String path) {
        throw new UnsupportedOperationException("getRawFile not supported by " + getClass().getSimpleName());
    }

    /**
     * Read a repository file, resolving {@code ref} to the default branch when null.
     * Empty when the file does not exist. Used to read per-repository configuration
     * over the API, before any container exists.
     */
    default Optional<String> readRepositoryFile(String owner, String repo, String path, String ref) {
        throw new UnsupportedOperationException("readRepositoryFile not supported by " + getClass().getSimpleName());
    }

    /** List file paths under a repository directory, resolving {@code ref} as above. */
    default List<String> listRepositoryFiles(String owner, String repo, String path, String ref) {
        throw new UnsupportedOperationException("listRepositoryFiles not supported by " + getClass().getSimpleName());
    }

    /** Delete a file on a branch with a single commit. */
    default void deleteFile(String owner, String repo, String branch, String path, String message) {
        throw new UnsupportedOperationException("deleteFile not supported by " + getClass().getSimpleName());
    }

    /**
     * Find the first branch whose name starts with the given prefix, or null.
     * Used to locate smithy's work branch ("smithy/&lt;ref&gt;-&lt;slug&gt;") from an issue ref.
     */
    default String findBranchByPrefix(String owner, String repo, String prefix) {
        throw new UnsupportedOperationException("findBranchByPrefix not supported by " + getClass().getSimpleName());
    }

    // URL helpers (provider-specific URL patterns)
    String fileBrowseUrl(String repoHtmlUrl, String branch, String path);

    String prUrl(String externalBaseUrl, String owner, String repo, int number);

    String issueUrl(String externalBaseUrl, String owner, String repo, String issueRef);

    String cloneUrl(String owner, String repo);

    String baseUrl();
}
