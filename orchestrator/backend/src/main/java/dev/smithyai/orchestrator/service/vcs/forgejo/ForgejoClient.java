package dev.smithyai.orchestrator.service.vcs.forgejo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.forgejoclient.ApiClient;
import dev.smithyai.forgejoclient.ApiException;
import dev.smithyai.forgejoclient.api.IssueApi;
import dev.smithyai.forgejoclient.api.RepositoryApi;
import dev.smithyai.forgejoclient.model.*;
import dev.smithyai.forgejoclient.model.CreateIssueOption;
import dev.smithyai.forgejoclient.model.IssueLabelsOption;
import dev.smithyai.orchestrator.runtime.actions.Capability;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.dto.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Slf4j
public class ForgejoClient implements VcsClient, IssueTrackerClient {

    /**
     * Forgejo reads repository files but has no reaction, discussion-reply or
     * file-delete API here.
     */
    @Override
    public java.util.Set<Capability> capabilities() {
        return java.util.EnumSet.of(
            Capability.PR_CREATE,
            Capability.PR_COMMENT,
            Capability.PR_REVIEW_INLINE,
            Capability.PR_REQUEST_REVIEW,
            Capability.ISSUE_COMMENT,
            Capability.ISSUE_ASSIGN,
            Capability.ISSUE_CREATE,
            Capability.ISSUE_LABEL,
            Capability.FILE_READ
        );
    }

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
    public IssueData createIssue(String owner, String repo, String title, String body, List<String> labels) {
        var option = new CreateIssueOption().title(title).body(body == null ? "" : body);
        Issue created = api(() -> issueApi.issueCreateIssue(owner, repo, option));
        // Labels go on afterwards: CreateIssueOption types them as numeric ids,
        // and a workflow names them.
        if (labels != null && !labels.isEmpty()) {
            labels.forEach(label -> addIssueLabel(owner, repo, String.valueOf(created.getNumber()), label));
            return getIssue(owner, repo, String.valueOf(created.getNumber()));
        }
        return toIssueData(created);
    }

    @Override
    public void addIssueLabel(String owner, String repo, String issueRef, String label) {
        long number = Long.parseLong(issueRef);
        // Forgejo takes either a label id or its name here, and a workflow only
        // knows the name.
        var option = new IssueLabelsOption().labels(List.of(label));
        api(() -> issueApi.issueAddLabel(owner, repo, number, option));
    }

    @Override
    public IssueData getIssue(String owner, String repo, String issueRef) {
        long number = Long.parseLong(issueRef);
        Issue issue = api(() -> issueApi.issueGetIssue(owner, repo, number));
        return toIssueData(issue);
    }

    @Override
    public List<CommentEntry> getIssueComments(String owner, String repo, String issueRef) {
        long number = Long.parseLong(issueRef);
        List<Comment> comments = api(() -> issueApi.issueGetComments(owner, repo, number, null, null));
        return comments.stream().map(this::toCommentEntry).toList();
    }

    @Override
    public CommentEntry createIssueComment(String owner, String repo, String issueRef, String body) {
        long number = Long.parseLong(issueRef);
        Comment comment = api(() ->
            issueApi.issueCreateComment(owner, repo, number, new CreateIssueCommentOption().body(body))
        );
        return toCommentEntry(comment);
    }

    @Override
    public void setIssueAssignees(String owner, String repo, String issueRef, List<String> assignees) {
        long number = Long.parseLong(issueRef);
        var opt = new EditIssueOption();
        opt.setAssignees(assignees);
        apiVoid(() -> issueApi.issueEditIssue(owner, repo, number, opt));
    }

    @Override
    public List<AttachmentInfo> getIssueAttachments(String owner, String repo, String issueRef) {
        long number = Long.parseLong(issueRef);
        List<Attachment> attachments = api(() -> issueApi.issueListIssueAttachments(owner, repo, number));
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
        return getIssueComments(owner, repo, String.valueOf(prNumber));
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
        setIssueAssignees(owner, repo, String.valueOf(prNumber), assignees);
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

    @Override
    public Optional<String> readRepositoryFile(String owner, String repo, String path, String ref) {
        try {
            // Same reason as listRepositoryFiles: the generated client percent-
            // encodes the separators inside a path, and Forgejo answers 400 to
            // `.smithy%2Fconfig.yml`. Every path this reads is multi-segment, so
            // that failure was total — and silent, because callers treat an
            // unreadable per-repository config as "there isn't one".
            String url = "%s/api/v1/repos/%s/%s/contents/%s?ref=%s".formatted(
                baseUrl,
                encode(owner),
                encode(repo),
                encodePath(path),
                encode(resolveRef(owner, repo, ref))
            );
            JsonNode node = readJson(url);
            if (node == null || node.isMissingNode()) return Optional.empty();
            var contents = new dev.smithyai.forgejoclient.model.ContentsResponse();
            contents.setContent(node.path("content").isMissingNode() ? null : node.path("content").asText());
            contents.setEncoding(node.path("encoding").asText("base64"));
            String content = contents.getContent();
            if (content == null) return Optional.empty();
            if ("base64".equalsIgnoreCase(contents.getEncoding())) {
                // Forgejo wraps base64 payloads, so strip whitespace before decoding.
                String normalized = content.replaceAll("\\s+", "");
                return Optional.of(new String(Base64.getDecoder().decode(normalized), StandardCharsets.UTF_8));
            }
            return Optional.of(content);
        } catch (ForgejoApiException e) {
            if (e.isNotFound()) return Optional.empty();
            throw e;
        }
    }

    @Override
    public List<String> listRepositoryFiles(String owner, String repo, String path, String ref) {
        // The generated repoGetContentsList only lists the repository root, and
        // repoGetContents types a directory listing as a single ContentsResponse,
        // so go to the contents endpoint directly for an arbitrary directory.
        try {
            String url = "%s/api/v1/repos/%s/%s/contents/%s?ref=%s".formatted(
                baseUrl,
                encode(owner),
                encode(repo),
                encodePath(path),
                encode(resolveRef(owner, repo, ref))
            );
            JsonNode node = readJson(url);
            if (node == null || !node.isArray()) return List.of();

            var files = new ArrayList<String>();
            for (JsonNode item : node) {
                if ("file".equals(item.path("type").asText(""))) {
                    String itemPath = item.path("path").asText("");
                    if (!itemPath.isBlank()) files.add(itemPath);
                }
            }
            return files;
        } catch (HttpClientErrorException.NotFound e) {
            return List.of();
        } catch (ForgejoApiException e) {
            if (e.isNotFound()) return List.of();
            throw e;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Percent-encode each path segment, leaving the separators intact. */
    private static String encodePath(String path) {
        return Arrays.stream(path.split("/")).map(ForgejoClient::encode).collect(Collectors.joining("/"));
    }

    /** The given ref, or the repository's default branch when it is null or blank. */
    private String resolveRef(String owner, String repo, String ref) {
        if (ref != null && !ref.isBlank()) return ref;
        return api(() -> repoApi.repoGet(owner, repo)).getDefaultBranch();
    }

    // ── VcsClient: URL helpers ───────────────────────────────

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Fetch and parse JSON from a raw URL.
     *
     * <p>Asked for as text rather than as a tree: Spring Boot 4 ships Jackson 3,
     * whose message converter cannot construct the Jackson 2 node type the rest
     * of this class works with, and the failure is a conversion error rather
     * than anything that names the real problem.
     */
    private JsonNode readJson(String url) {
        String body = rest.get().uri(URI.create(url)).retrieve().body(String.class);
        if (body == null || body.isBlank()) return null;
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            throw new ForgejoApiException(502, "Unreadable JSON from " + url, e);
        }
    }

    @Override
    public String fileBrowseUrl(String repoHtmlUrl, String branch, String path) {
        return repoHtmlUrl + "/src/branch/" + branch + "/" + path;
    }

    @Override
    public String prUrl(String externalBaseUrl, String owner, String repo, int number) {
        return externalBaseUrl + "/" + owner + "/" + repo + "/pulls/" + number;
    }

    @Override
    public String issueUrl(String externalBaseUrl, String owner, String repo, String issueRef) {
        return externalBaseUrl + "/" + owner + "/" + repo + "/issues/" + issueRef;
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
            String.valueOf(issue.getNumber()),
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
