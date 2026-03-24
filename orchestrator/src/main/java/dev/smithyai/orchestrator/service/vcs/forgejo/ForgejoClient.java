package dev.smithyai.orchestrator.service.vcs.forgejo;

import dev.smithyai.forgejoclient.ApiClient;
import dev.smithyai.forgejoclient.ApiException;
import dev.smithyai.forgejoclient.api.IssueApi;
import dev.smithyai.forgejoclient.api.RepositoryApi;
import dev.smithyai.forgejoclient.model.*;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.dto.*;
import java.net.URI;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

@Slf4j
public class ForgejoClient implements VcsClient, IssueTrackerClient {

    private final String baseUrl;
    private final IssueApi issueApi;
    private final RepositoryApi repoApi;
    private final RestClient rest;

    public ForgejoClient(String baseUrl, String token) {
        this.baseUrl = baseUrl;

        var apiClient = new ApiClient();
        apiClient.setBasePath(baseUrl + "/api/v1");
        apiClient.addDefaultHeader("Authorization", "token " + token);

        this.issueApi = new IssueApi(apiClient);
        this.repoApi = new RepositoryApi(apiClient);

        // Keep a RestClient for downloadAttachment (URL rewriting not in SDK)
        this.rest = RestClient.builder().defaultHeader("Authorization", "token " + token).build();
    }

    @FunctionalInterface
    private interface ApiCall<T> {
        T call() throws ApiException;
    }

    @FunctionalInterface
    private interface ApiVoidCall {
        void call() throws ApiException;
    }

    private <T> T api(ApiCall<T> call) {
        try {
            return call.call();
        } catch (ApiException e) {
            throw new ForgejoApiException(e.getCode(), e.getMessage(), e);
        }
    }

    private void apiVoid(ApiVoidCall call) {
        try {
            call.call();
        } catch (ApiException e) {
            throw new ForgejoApiException(e.getCode(), e.getMessage(), e);
        }
    }

    // ── IssueTrackerClient ───────────────────────────────────

    @Override
    public IssueData getIssue(String owner, String repo, int number) {
        Issue issue = api(() -> issueApi.issueGetIssue(owner, repo, (long) number));
        return toIssueData(issue);
    }

    @Override
    public List<CommentEntry> getIssueComments(String owner, String repo, int number) {
        List<Comment> comments = api(() -> issueApi.issueGetComments(owner, repo, (long) number, null, null));
        return comments.stream().map(this::toCommentEntry).toList();
    }

    @Override
    public CommentEntry createIssueComment(String owner, String repo, int number, String body) {
        Comment comment = api(() ->
            issueApi.issueCreateComment(owner, repo, (long) number, new CreateIssueCommentOption().body(body))
        );
        return toCommentEntry(comment);
    }

    @Override
    public void setIssueAssignees(String owner, String repo, int issueNumber, List<String> assignees) {
        var opt = new EditIssueOption();
        opt.setAssignees(assignees);
        apiVoid(() -> issueApi.issueEditIssue(owner, repo, (long) issueNumber, opt));
    }

    @Override
    public List<AttachmentInfo> getIssueAttachments(String owner, String repo, int number) {
        List<Attachment> attachments = api(() -> issueApi.issueListIssueAttachments(owner, repo, (long) number));
        return attachments.stream().map(this::toAttachmentInfo).toList();
    }

    @Override
    public List<AttachmentInfo> getCommentAttachments(String owner, String repo, long commentId) {
        List<Attachment> attachments = api(() -> issueApi.issueListIssueCommentAttachments(owner, repo, commentId));
        return attachments.stream().map(this::toAttachmentInfo).toList();
    }

    @Override
    public byte[] downloadAttachment(String downloadUrl) {
        // Rewrite host to internal Forgejo URL
        URI publicUri = URI.create(downloadUrl);
        String internalUrl = downloadUrl.replaceFirst(
            java.util.regex.Pattern.quote(publicUri.getScheme() + "://" + publicUri.getAuthority()),
            baseUrl
        );

        return rest.get().uri(URI.create(internalUrl)).retrieve().body(byte[].class);
    }

    // ── VcsClient: Pull Requests ─────────────────────────────

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
        String prTitle = draft ? "WIP: " + title : title;
        var opt = new CreatePullRequestOption();
        opt.setTitle(prTitle);
        opt.setHead(head);
        opt.setBase(base);
        opt.setBody(body);
        PullRequest pr = api(() -> repoApi.repoCreatePullRequest(owner, repo, opt));
        return toPrData(pr);
    }

    @Override
    public PrData getPullRequest(String owner, String repo, int number) {
        PullRequest pr = api(() -> repoApi.repoGetPullRequest(owner, repo, (long) number));
        return toPrData(pr);
    }

    @Override
    public PrData findPrByHead(String owner, String repo, String head) {
        List<PullRequest> prs = api(() ->
            repoApi.repoListPullRequests(owner, repo, "open", null, null, null, null, null, null)
        );
        for (var pr : prs) {
            if (pr.getHead() != null && head.equals(pr.getHead().getRef())) {
                return toPrData(pr);
            }
        }
        return null;
    }

    @Override
    public void createPrComment(String owner, String repo, int prNumber, String body) {
        // In Forgejo, PRs are issues — PR comments go through the issue comment API
        api(() -> issueApi.issueCreateComment(owner, repo, (long) prNumber, new CreateIssueCommentOption().body(body)));
    }

    @Override
    public List<CommentEntry> getPrComments(String owner, String repo, int prNumber) {
        return getIssueComments(owner, repo, prNumber);
    }

    // ── VcsClient: Reviews ───────────────────────────────────

    @Override
    public void createPullReview(
        String owner,
        String repo,
        int prNumber,
        String body,
        String event,
        List<InlineComment> comments
    ) {
        var opt = new CreatePullReviewOptions();
        opt.setBody(body);
        opt.setEvent(event != null ? event : "COMMENT");
        if (comments != null && !comments.isEmpty()) {
            var reviewComments = new ArrayList<CreatePullReviewComment>();
            for (var c : comments) {
                var rc = new CreatePullReviewComment();
                rc.setPath(c.path());
                rc.setBody(c.body());
                rc.setNewPosition(c.newPosition());
                reviewComments.add(rc);
            }
            opt.setComments(reviewComments);
        }
        apiVoid(() -> repoApi.repoCreatePullReview(owner, repo, (long) prNumber, opt));
    }

    @Override
    public List<ReviewEntry> getPrReviews(String owner, String repo, int prNumber) {
        List<PullReview> reviews = api(() -> repoApi.repoListPullReviews(owner, repo, (long) prNumber, null, null));
        return reviews.stream().map(this::toReviewEntry).toList();
    }

    @Override
    public List<ReviewCommentEntry> getReviewComments(String owner, String repo, int prNumber, long reviewId) {
        List<PullReviewComment> comments = api(() ->
            repoApi.repoGetPullReviewComments(owner, repo, (long) prNumber, reviewId)
        );
        return comments.stream().map(this::toReviewCommentEntry).toList();
    }

    @Override
    public LatestReviewResult getLatestReviewComments(String owner, String repo, int prNumber, String reviewer) {
        List<PullReview> reviews = api(() -> repoApi.repoListPullReviews(owner, repo, (long) prNumber, null, null));

        PullReview target = null;
        for (int i = reviews.size() - 1; i >= 0; i--) {
            var r = reviews.get(i);
            if (r.getUser() != null && reviewer.equals(r.getUser().getLogin())) {
                target = r;
                break;
            }
        }
        if (target == null) return new LatestReviewResult(List.of(), "");

        long reviewId = target.getId();
        String reviewBody = target.getBody() != null ? target.getBody() : "";
        var comments = getReviewComments(owner, repo, prNumber, reviewId);
        return new LatestReviewResult(comments, reviewBody);
    }

    // ── VcsClient: Assignees & Reviewers ─────────────────────

    @Override
    public void setPrAssignees(String owner, String repo, int prNumber, List<String> assignees) {
        // In Forgejo, PR assignees are set via the issue API
        setIssueAssignees(owner, repo, prNumber, assignees);
    }

    @Override
    public void requestReview(String owner, String repo, int prNumber, List<String> reviewers) {
        apiVoid(() ->
            repoApi.repoCreatePullReviewRequests(
                owner,
                repo,
                (long) prNumber,
                new PullReviewRequestOptions().reviewers(reviewers)
            )
        );
    }

    @Override
    public boolean isAssigned(String owner, String repo, int prNumber, String username) {
        var pr = getPullRequest(owner, repo, prNumber);
        return pr.assignees() != null && pr.assignees().contains(username);
    }

    // ── VcsClient: Repository ────────────────────────────────

    @Override
    public boolean repoExists(String owner, String repo) {
        try {
            api(() -> repoApi.repoGet(owner, repo));
            return true;
        } catch (ForgejoApiException e) {
            if (e.isNotFound()) return false;
            throw e;
        }
    }

    // ── VcsClient: URL helpers ───────────────────────────────

    @Override
    public String fileBrowseUrl(String repoHtmlUrl, String branch, String path) {
        return repoHtmlUrl + "/src/branch/" + branch + "/" + path;
    }

    @Override
    public String prUrl(String externalBaseUrl, String owner, String repo, int number) {
        return externalBaseUrl + "/" + owner + "/" + repo + "/pulls/" + number;
    }

    @Override
    public String cloneUrl(String owner, String repo) {
        return baseUrl + "/" + owner + "/" + repo + ".git";
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    // ── DTO conversion helpers ───────────────────────────────

    private IssueData toIssueData(Issue issue) {
        List<String> assigneeLogins =
            issue.getAssignees() != null ? issue.getAssignees().stream().map(User::getLogin).toList() : List.of();
        List<String> labelNames =
            issue.getLabels() != null ? issue.getLabels().stream().map(Label::getName).toList() : List.of();
        return new IssueData(
            issue.getNumber().intValue(),
            issue.getTitle(),
            issue.getBody(),
            issue.getState() != null ? issue.getState() : "open",
            assigneeLogins,
            issue.getRef(),
            labelNames
        );
    }

    private PrData toPrData(PullRequest pr) {
        List<String> assigneeLogins =
            pr.getAssignees() != null ? pr.getAssignees().stream().map(User::getLogin).toList() : List.of();
        return new PrData(
            pr.getNumber().intValue(),
            pr.getTitle() != null ? pr.getTitle() : "",
            pr.getBody() != null ? pr.getBody() : "",
            Boolean.TRUE.equals(pr.getMerged()),
            pr.getHead() != null ? pr.getHead().getRef() : "",
            pr.getBase() != null ? pr.getBase().getRef() : "",
            assigneeLogins
        );
    }

    private CommentEntry toCommentEntry(Comment comment) {
        return new CommentEntry(
            comment.getId(),
            comment.getUser() != null ? comment.getUser().getLogin() : "",
            comment.getBody(),
            comment.getCreatedAt()
        );
    }

    private AttachmentInfo toAttachmentInfo(Attachment attachment) {
        return new AttachmentInfo(attachment.getId(), attachment.getName(), attachment.getBrowserDownloadUrl());
    }

    private ReviewEntry toReviewEntry(PullReview review) {
        return new ReviewEntry(
            review.getId(),
            review.getUser() != null ? review.getUser().getLogin() : "",
            review.getBody() != null ? review.getBody() : "",
            review.getState() != null ? review.getState() : "",
            review.getCommitId() != null ? review.getCommitId() : "",
            review.getSubmittedAt()
        );
    }

    private ReviewCommentEntry toReviewCommentEntry(PullReviewComment comment) {
        return new ReviewCommentEntry(
            comment.getUser() != null ? comment.getUser().getLogin() : "",
            comment.getBody(),
            comment.getPath() != null ? comment.getPath() : "",
            comment.getPosition() != null ? comment.getPosition() : 0,
            comment.getCreatedAt()
        );
    }
}
