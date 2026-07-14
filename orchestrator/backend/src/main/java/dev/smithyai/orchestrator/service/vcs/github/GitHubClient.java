package dev.smithyai.orchestrator.service.vcs.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.dto.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GitHubClient implements VcsClient, IssueTrackerClient {

    private static final String GITHUB_COM_API = "https://api.github.com";
    private static final String GITHUB_COM_WEB = "https://github.com";
    private static final String GITHUB_API_VERSION = "2022-11-28";

    private final String apiUrl;
    private final String webUrl;
    private final String token;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public GitHubClient(String url, String externalUrl, String token) {
        boolean isGithubCom = url == null || url.isBlank() || GITHUB_COM_WEB.equals(url.strip());
        if (isGithubCom) {
            this.apiUrl = GITHUB_COM_API;
            this.webUrl = GITHUB_COM_WEB;
        } else {
            String base = url.strip();
            this.apiUrl = base + "/api/v3";
            this.webUrl = externalUrl != null && !externalUrl.isBlank() ? externalUrl.strip() : base;
        }
        this.token = token;
        this.http = HttpClient.newBuilder().build();
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    // ── IssueTrackerClient ───────────────────────────────────

    @Override
    public IssueData getIssue(String owner, String repo, int number) {
        var node = get("/repos/%s/%s/issues/%d", owner, repo, number);
        return toIssueData(node);
    }

    @Override
    public List<CommentEntry> getIssueComments(String owner, String repo, int number) {
        return fetchPaged("/repos/%s/%s/issues/%d/comments?per_page=100".formatted(owner, repo, number));
    }

    @Override
    public CommentEntry createIssueComment(String owner, String repo, int number, String body) {
        var node = post("/repos/%s/%s/issues/%d/comments", Map.of("body", body), owner, repo, number);
        return toCommentEntry(node);
    }

    @Override
    public void setIssueAssignees(String owner, String repo, int number, List<String> assignees) {
        patch("/repos/%s/%s/issues/%d", Map.of("assignees", assignees), owner, repo, number);
    }

    @Override
    public List<AttachmentInfo> getIssueAttachments(String owner, String repo, int number) {
        return List.of();
    }

    @Override
    public List<AttachmentInfo> getCommentAttachments(String owner, String repo, long commentId) {
        return List.of();
    }

    @Override
    public byte[] downloadAttachment(String url) {
        try {
            var request = buildRequest(url).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("GitHub attachment download failed: " + response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("GitHub attachment download failed", e);
        }
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
        var params = new LinkedHashMap<String, Object>();
        params.put("title", title);
        params.put("head", head);
        params.put("base", base);
        params.put("body", body);
        params.put("draft", draft);
        var node = post("/repos/%s/%s/pulls", params, owner, repo);
        return toPrData(node);
    }

    @Override
    public PrData getPullRequest(String owner, String repo, int number) {
        var node = get("/repos/%s/%s/pulls/%d", owner, repo, number);
        return toPrData(node);
    }

    @Override
    public PrData findPrByHead(String owner, String repo, String head) {
        // GitHub requires "owner:branch" format for the head filter
        var nodes = getList("/repos/%s/%s/pulls?state=open&head=%s:%s&per_page=10", owner, repo, owner, head);
        if (nodes.isEmpty()) return null;
        return toPrData(nodes.getFirst());
    }

    @Override
    public void createPrComment(String owner, String repo, int prNumber, String body) {
        post("/repos/%s/%s/issues/%d/comments", Map.of("body", body), owner, repo, prNumber);
    }

    @Override
    public List<CommentEntry> getPrComments(String owner, String repo, int prNumber) {
        return fetchPaged("/repos/%s/%s/issues/%d/comments?per_page=100".formatted(owner, repo, prNumber));
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
        var params = new LinkedHashMap<String, Object>();
        if (body != null && !body.isBlank()) {
            params.put("body", body);
        }
        params.put("event", event != null ? event : "COMMENT");
        if (comments != null && !comments.isEmpty()) {
            var reviewComments = new ArrayList<Map<String, Object>>();
            for (var c : comments) {
                var rc = new LinkedHashMap<String, Object>();
                rc.put("path", c.path());
                rc.put("line", c.newPosition());
                rc.put("side", "RIGHT");
                rc.put("body", c.body());
                reviewComments.add(rc);
            }
            params.put("comments", reviewComments);
        }
        post("/repos/%s/%s/pulls/%d/reviews", params, owner, repo, prNumber);
    }

    @Override
    public List<ReviewEntry> getPrReviews(String owner, String repo, int prNumber) {
        var nodes = getList("/repos/%s/%s/pulls/%d/reviews?per_page=100", owner, repo, prNumber);
        return nodes.stream().map(this::toReviewEntry).toList();
    }

    @Override
    public List<ReviewCommentEntry> getReviewComments(String owner, String repo, int prNumber, long reviewId) {
        var nodes = getList("/repos/%s/%s/pulls/%d/reviews/%d/comments", owner, repo, prNumber, reviewId);
        return nodes.stream().map(this::toReviewCommentEntry).toList();
    }

    @Override
    public LatestReviewResult getLatestReviewComments(String owner, String repo, int prNumber, String reviewer) {
        var reviews = getList("/repos/%s/%s/pulls/%d/reviews?per_page=100", owner, repo, prNumber);

        JsonNode latestReview = null;
        for (int i = reviews.size() - 1; i >= 0; i--) {
            var r = reviews.get(i);
            if (reviewer.equals(r.path("user").path("login").asText(""))) {
                latestReview = r;
                break;
            }
        }
        if (latestReview == null) return new LatestReviewResult(List.of(), "");

        long reviewId = latestReview.path("id").asLong();
        String reviewBody = latestReview.path("body").asText("");
        var comments = getReviewComments(owner, repo, prNumber, reviewId);
        return new LatestReviewResult(comments, reviewBody);
    }

    // ── VcsClient: Assignees & Reviewers ─────────────────────

    @Override
    public void setPrAssignees(String owner, String repo, int prNumber, List<String> assignees) {
        patch("/repos/%s/%s/issues/%d", Map.of("assignees", assignees), owner, repo, prNumber);
    }

    @Override
    public void requestReview(String owner, String repo, int prNumber, List<String> reviewers) {
        post(
            "/repos/%s/%s/pulls/%d/requested_reviewers",
            Map.of("reviewers", reviewers),
            owner,
            repo,
            prNumber
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
            get("/repos/%s/%s", owner, repo);
            return true;
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) return false;
            throw e;
        }
    }

    // ── VcsClient: URL helpers ───────────────────────────────

    @Override
    public String fileBrowseUrl(String repoHtmlUrl, String branch, String path) {
        return repoHtmlUrl + "/blob/" + branch + "/" + path;
    }

    @Override
    public String prUrl(String externalBaseUrl, String owner, String repo, int number) {
        return externalBaseUrl + "/" + owner + "/" + repo + "/pull/" + number;
    }

    @Override
    public String cloneUrl(String owner, String repo) {
        return webUrl + "/" + owner + "/" + repo + ".git";
    }

    @Override
    public String baseUrl() {
        return webUrl;
    }

    // ── HTTP helpers ─────────────────────────────────────────

    private JsonNode get(String pathTemplate, Object... args) {
        String path = pathTemplate.formatted(args);
        try {
            var request = buildRequest(apiUrl + path).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException(
                    "GitHub API error %d on GET %s: %s".formatted(response.statusCode(), path, response.body())
                );
            }
            return mapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("GitHub API request failed: GET " + path, e);
        }
    }

    private record PagedResult(List<JsonNode> items, String nextUrl) {}

    private PagedResult getPage(String url) {
        try {
            String fullUrl = url.startsWith("http") ? url : (apiUrl + url);
            var request = buildRequest(fullUrl).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException(
                    "GitHub API error %d on GET %s: %s".formatted(response.statusCode(), url, response.body())
                );
            }
            var node = mapper.readTree(response.body());
            var items = new ArrayList<JsonNode>();
            if (node.isArray()) {
                for (var item : node) items.add(item);
            }
            String nextUrl = extractNextLink(response.headers().firstValue("Link").orElse(""));
            return new PagedResult(items, nextUrl);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("GitHub API request failed: GET " + url, e);
        }
    }

    private List<CommentEntry> fetchPaged(String startPath) {
        var result = new ArrayList<CommentEntry>();
        String url = startPath;
        while (url != null) {
            var page = getPage(url);
            for (var n : page.items()) result.add(toCommentEntry(n));
            url = page.nextUrl();
        }
        return result;
    }

    private List<JsonNode> getList(String pathTemplate, Object... args) {
        var node = get(pathTemplate, args);
        if (node.isArray()) {
            var list = new ArrayList<JsonNode>();
            for (var item : node) list.add(item);
            return list;
        }
        return List.of();
    }

    private JsonNode post(String pathTemplate, Map<String, Object> body, Object... args) {
        String path = pathTemplate.formatted(args);
        try {
            String json = mapper.writeValueAsString(body);
            var request = buildRequest(apiUrl + path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException(
                    "GitHub API error %d on POST %s: %s".formatted(response.statusCode(), path, response.body())
                );
            }
            return mapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("GitHub API request failed: POST " + path, e);
        }
    }

    private void patch(String pathTemplate, Map<String, Object> body, Object... args) {
        String path = pathTemplate.formatted(args);
        try {
            String json = mapper.writeValueAsString(body);
            var request = buildRequest(apiUrl + path)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException(
                    "GitHub API error %d on PATCH %s: %s".formatted(response.statusCode(), path, response.body())
                );
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("GitHub API request failed: PATCH " + path, e);
        }
    }

    private HttpRequest.Builder buildRequest(String url) {
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION);
    }

    private static String extractNextLink(String linkHeader) {
        if (linkHeader == null || linkHeader.isBlank()) return null;
        for (String part : linkHeader.split(",")) {
            String[] segments = part.trim().split(";");
            if (segments.length == 2 && segments[1].trim().equals("rel=\"next\"")) {
                return segments[0].trim().replaceAll("[<>]", "");
            }
        }
        return null;
    }

    // ── DTO converters ───────────────────────────────────────

    private IssueData toIssueData(JsonNode node) {
        List<String> assignees = new ArrayList<>();
        if (node.has("assignees") && node.get("assignees").isArray()) {
            for (var a : node.get("assignees")) assignees.add(a.path("login").asText(""));
        }
        List<String> labels = new ArrayList<>();
        if (node.has("labels") && node.get("labels").isArray()) {
            for (var l : node.get("labels")) labels.add(l.path("name").asText(""));
        }
        return new IssueData(
            node.path("number").asInt(),
            node.path("title").asText(""),
            node.path("body").asText(""),
            node.path("state").asText("open"),
            assignees,
            "",
            labels
        );
    }

    private PrData toPrData(JsonNode node) {
        List<String> assignees = new ArrayList<>();
        if (node.has("assignees") && node.get("assignees").isArray()) {
            for (var a : node.get("assignees")) assignees.add(a.path("login").asText(""));
        }
        return new PrData(
            node.path("number").asInt(),
            node.path("title").asText(""),
            node.path("body").asText(""),
            node.path("merged").asBoolean(false),
            node.path("head").path("ref").asText(""),
            node.path("base").path("ref").asText(""),
            assignees
        );
    }

    private CommentEntry toCommentEntry(JsonNode node) {
        return new CommentEntry(
            node.path("id").asLong(),
            node.path("user").path("login").asText(""),
            node.path("body").asText(""),
            parseDateTime(node.path("created_at").asText(""))
        );
    }

    private ReviewEntry toReviewEntry(JsonNode node) {
        return new ReviewEntry(
            node.path("id").asLong(),
            node.path("user").path("login").asText(""),
            node.path("body").asText(""),
            node.path("state").asText(""),
            node.path("commit_id").asText(""),
            parseDateTime(node.path("submitted_at").asText(""))
        );
    }

    private ReviewCommentEntry toReviewCommentEntry(JsonNode node) {
        return new ReviewCommentEntry(
            node.path("user").path("login").asText(""),
            node.path("body").asText(""),
            node.path("path").asText(""),
            node.path("line").asInt(node.path("original_line").asInt(0)),
            parseDateTime(node.path("created_at").asText(""))
        );
    }

    private OffsetDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }
}
