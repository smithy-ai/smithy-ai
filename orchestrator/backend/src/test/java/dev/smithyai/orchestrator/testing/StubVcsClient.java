package dev.smithyai.orchestrator.testing;

import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.dto.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-memory VCS and issue tracker. Records what a flow asked it to do so a
 * test can assert on side effects — comments posted, PRs opened, labels set —
 * rather than on internal calls.
 */
public class StubVcsClient implements VcsClient, IssueTrackerClient {

    /** A stub stands in for every provider, so it declares everything. */
    @Override
    public java.util.Set<dev.smithyai.orchestrator.runtime.actions.Capability> capabilities() {
        return java.util.EnumSet.allOf(dev.smithyai.orchestrator.runtime.actions.Capability.class);
    }

    public final List<String> issueComments = new ArrayList<>();
    public final List<String> prComments = new ArrayList<>();
    public final List<PrData> createdPrs = new ArrayList<>();
    public final List<String> createdIssues = new ArrayList<>();
    public final Map<String, String> repositoryFiles = new ConcurrentHashMap<>();

    /** Repositories that exist; anything not listed is reported missing. */
    public final List<String> existingRepos = new ArrayList<>(List.of("acme/app", "acme/app-context"));

    private final AtomicInteger nextPrNumber = new AtomicInteger(100);

    // ── IssueTrackerClient ───────────────────────────────────

    @Override
    public IssueData getIssue(String owner, String repo, String issueRef) {
        return new IssueData(
            issueRef,
            "Add a thing",
            "Please add the thing.",
            "open",
            List.of("smithy"),
            "main",
            List.of()
        );
    }

    @Override
    public List<CommentEntry> getIssueComments(String owner, String repo, String issueRef) {
        return List.of();
    }

    @Override
    public CommentEntry createIssueComment(String owner, String repo, String issueRef, String body) {
        issueComments.add(body);
        return new CommentEntry(issueComments.size(), "smithy", body, OffsetDateTime.now());
    }

    @Override
    public IssueData createIssue(String owner, String repo, String title, String body, List<String> labels) {
        createdIssues.add(title);
        return new IssueData(
            String.valueOf(createdIssues.size()),
            title,
            body,
            "open",
            List.of(),
            "main",
            labels == null ? List.of() : labels
        );
    }

    @Override
    public void addIssueLabel(String owner, String repo, String issueRef, String label) {}

    @Override
    public void setIssueAssignees(String owner, String repo, String issueRef, List<String> assignees) {}

    @Override
    public List<AttachmentInfo> getIssueAttachments(String owner, String repo, String issueRef) {
        return List.of();
    }

    @Override
    public List<AttachmentInfo> getCommentAttachments(String owner, String repo, long commentId) {
        return List.of();
    }

    @Override
    public byte[] downloadAttachment(String url) {
        return new byte[0];
    }

    // ── VcsClient ────────────────────────────────────────────

    @Override
    public PrData createPullRequest(
        String owner,
        String repo,
        String title,
        String head,
        String base,
        String body,
        boolean draft
    ) {
        var pr = new PrData(nextPrNumber.getAndIncrement(), title, body, false, head, base, List.of());
        createdPrs.add(pr);
        return pr;
    }

    @Override
    public PrData getPullRequest(String owner, String repo, int number) {
        return createdPrs
            .stream()
            .filter(pr -> pr.number() == number)
            .findFirst()
            .orElse(new PrData(number, "PR", "", false, "smithy/7-thing", "main", List.of()));
    }

    @Override
    public PrData findPrByHead(String owner, String repo, String head) {
        return createdPrs
            .stream()
            .filter(pr -> head.equals(pr.headRef()))
            .findFirst()
            .orElse(null);
    }

    @Override
    public void createPrComment(String owner, String repo, int prNumber, String body) {
        prComments.add(body);
    }

    @Override
    public void createPullReview(
        String owner,
        String repo,
        int prNumber,
        String body,
        String event,
        List<InlineComment> comments
    ) {
        prComments.add(body);
    }

    @Override
    public List<CommentEntry> getPrComments(String owner, String repo, int prNumber) {
        return List.of();
    }

    @Override
    public List<ReviewEntry> getPrReviews(String owner, String repo, int prNumber) {
        return List.of();
    }

    @Override
    public List<ReviewCommentEntry> getReviewComments(String owner, String repo, int prNumber, long reviewId) {
        return List.of();
    }

    @Override
    public LatestReviewResult getLatestReviewComments(String owner, String repo, int prNumber, String reviewer) {
        return new LatestReviewResult(List.of(), null);
    }

    @Override
    public void setPrAssignees(String owner, String repo, int prNumber, List<String> assignees) {}

    @Override
    public void requestReview(String owner, String repo, int prNumber, List<String> reviewers) {}

    @Override
    public boolean isAssigned(String owner, String repo, int prNumber, String username) {
        return true;
    }

    @Override
    public boolean repoExists(String owner, String repo) {
        return existingRepos.contains(owner + "/" + repo);
    }

    @Override
    public Optional<String> readRepositoryFile(String owner, String repo, String path, String ref) {
        return Optional.ofNullable(repositoryFiles.get(owner + "/" + repo + ":" + path));
    }

    @Override
    public List<String> listRepositoryFiles(String owner, String repo, String path, String ref) {
        return repositoryFiles
            .keySet()
            .stream()
            .filter(key -> key.startsWith(owner + "/" + repo + ":" + path))
            .map(key -> key.substring(key.indexOf(':') + 1))
            .toList();
    }

    @Override
    public String getRawFile(String owner, String repo, String branch, String path) {
        return repositoryFiles.get(owner + "/" + repo + ":" + path);
    }

    @Override
    public void deleteFile(String owner, String repo, String branch, String path, String message) {
        repositoryFiles.remove(owner + "/" + repo + ":" + path);
    }

    @Override
    public String findBranchByPrefix(String owner, String repo, String prefix) {
        return prefix + "placeholder";
    }

    @Override
    public String fileBrowseUrl(String repoHtmlUrl, String branch, String path) {
        return repoHtmlUrl + "/src/branch/" + branch + "/" + path;
    }

    @Override
    public String prUrl(String externalBaseUrl, String owner, String repo, int number) {
        return "%s/%s/%s/pulls/%d".formatted(externalBaseUrl, owner, repo, number);
    }

    @Override
    public String cloneUrl(String owner, String repo) {
        return "https://git.invalid/%s/%s.git".formatted(owner, repo);
    }

    @Override
    public String baseUrl() {
        return "https://git.invalid";
    }
}
