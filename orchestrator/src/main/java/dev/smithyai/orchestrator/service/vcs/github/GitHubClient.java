package dev.smithyai.orchestrator.service.vcs.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.dto.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GitHubClient implements VcsClient, IssueTrackerClient {

    private static final String DEFAULT_API_URL = "https://api.github.com";
    private static final String DEFAULT_EXTERNAL_URL = "https://github.com";
    private static final String GITHUB_API_VERSION = "2022-11-28";

    private final String baseUrl;
    private final String externalUrl;
    private final String token;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public GitHubClient(String baseUrl, String externalUrl, String token) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_API_URL : baseUrl;
        this.externalUrl = (externalUrl == null || externalUrl.isBlank()) ? DEFAULT_EXTERNAL_URL : externalUrl;
        this.token = token;
        this.http = HttpClient.newBuilder().build();
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    // ── IssueTrackerClient ───────────────────────────────────

    @Override
    public IssueData getIssue(String owner, String repo, int number) {
        var node = get("/repos/%s/%s/issues/%d", owner, repo, number);
        List<String> assignees = new ArrayList<>();
        if (node.has("assignees") && node.get("assignees").isArray()) {
            for (var a : node.get("assignees")) {
                assignees.add(a.path("login").asText(""));
            }
        }
        List<String> labels = new ArrayList<>();
        if (node.has("labels") && node.get("labels").isArray()) {
            for (var l : node.get("labels")) {
                labels.add(l.path("name").asText(""));
            }
        }
        return new IssueData(
            node.path("number").asInt(),
            node.path("title").asText(""),
            node.path("body").asText(""),
            node.path("state").asText("open"),
            assignees,
            "", // GitHub issues have no branch ref
            labels
        );
    }

    @Override
    public List<CommentEntry> getIssueComments(String owner, String repo, int number) {
        var nodes = getList("/repos/%s/%s/issues/%d/comments", owner, repo, number);
        var result = new ArrayList<CommentEntry>();
        for (var n : nodes) {
            result.add(
                new CommentEntry(
                    n.path("id").asLong(),
                    n.path("user").path("login").asText(""),
                    n.path("body").asText(""),
                    parseDateTime(n.path("created_at").asText(""))
                )
            );
        }
        return result;
    }

    @Override
    public CommentEntry createIssueComment(String owner, String repo, int number, String body) {
        var node = post("/repos/%s/%s/issues/%d/comments", Map.of("body", body), owner, repo, number);
        return new CommentEntry(
            node.path("id").asLong(),
            node.path("user").path("login").asText(""),
            node.path("body").asText(""),
            parseDateTime(node.path("created_at").asText(""))
        );
    }

    @Override
    public void setIssueAssignees(String owner, String repo, int number, List<String> assignees) {
        postVoid("/repos/%s/%s/issues/%d/assignees", Map.of("assignees", assignees), owner, repo, number);
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
        throw new UnsupportedOperationException("GitHub does not support attachment downloads");
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
        // GitHub requires owner:branch format; URL-encode to handle slashes in branch names
        String qualifiedHead = URLEncoder.encode(owner + ":" + head, StandardCharsets.UTF_8);
        var nodes = getList(
            "/repos/%s/%s/pulls?head=%s&state=open",
            owner,
            repo,
            qualifiedHead
        );
        if (nodes.isEmpty()) return null;
        return toPrData(nodes.getFirst());
    }

    @Override
    public void createPrComment(String owner, String repo, int prNumber, String body) {
        // GitHub uses issues endpoint for PR comments
        post("/repos/%s/%s/issues/%d/comments", Map.of("body", body), owner, repo, prNumber);
    }

    @Override
    public List<CommentEntry> getPrComments(String owner, String repo, int prNumber) {
        return List.of();
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
        var inlineComments = new ArrayList<Map<String, Object>>();
        if (comments != null) {
            for (var c : comments) {
                var cm = new LinkedHashMap<String, Object>();
                cm.put("path", c.path());
                cm.put("body", c.body());
                cm.put("position", c.newPosition());
                inlineComments.add(cm);
            }
        }
        var params = new LinkedHashMap<String, Object>();
        params.put("body", body != null ? body : "");
        params.put("event", event != null && !event.isBlank() ? event : "COMMENT");
        params.put("comments", inlineComments);
        post("/repos/%s/%s/pulls/%d/reviews", params, owner, repo, prNumber);
    }

    @Override
    public List<ReviewEntry> getPrReviews(String owner, String repo, int prNumber) {
        var nodes = getList("/repos/%s/%s/pulls/%d/reviews", owner, repo, prNumber);
        var result = new ArrayList<ReviewEntry>();
        for (var n : nodes) {
            result.add(
                new ReviewEntry(
                    n.path("id").asLong(),
                    n.path("user").path("login").asText(""),
                    n.path("body").asText(""),
                    n.path("state").asText(""),
                    n.path("commit_id").asText(""),
                    parseDateTime(n.path("submitted_at").asText(""))
                )
            );
        }
        return result;
    }

    @Override
    public List<ReviewCommentEntry> getReviewComments(String owner, String repo, int prNumber, long reviewId) {
        var nodes = getList("/repos/%s/%s/pulls/%d/reviews/%d/comments", owner, repo, prNumber, reviewId);
        var result = new ArrayList<ReviewCommentEntry>();
        for (var n : nodes) {
            result.add(
                new ReviewCommentEntry(
                    n.path("user").path("login").asText(""),
                    n.path("body").asText(""),
                    n.path("path").asText(""),
                    n.path("position").asInt(0),
                    parseDateTime(n.path("created_at").asText(""))
                )
            );
        }
        return result;
    }

    @Override
    public LatestReviewResult getLatestReviewComments(String owner, String repo, int prNumber, String reviewer) {
        var reviews = getPrReviews(owner, repo, prNumber);

        // Find the latest review by this reviewer (scan from end)
        ReviewEntry latestReview = null;
        for (int i = reviews.size() - 1; i >= 0; i--) {
            if (reviewer.equals(reviews.get(i).userLogin())) {
                latestReview = reviews.get(i);
                break;
            }
        }

        if (latestReview == null) {
            return new LatestReviewResult(List.of(), "");
        }

        var comments = getReviewComments(owner, repo, prNumber, latestReview.id());
        return new LatestReviewResult(comments, latestReview.body());
    }

    // ── VcsClient: Assignees & Reviewers ─────────────────────

    @Override
    public void setPrAssignees(String owner, String repo, int prNumber, List<String> assignees) {
        postVoid("/repos/%s/%s/issues/%d/assignees", Map.of("assignees", assignees), owner, repo, prNumber);
    }

    @Override
    public void requestReview(String owner, String repo, int prNumber, List<String> reviewers) {
        postVoid(
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
        // Use externalUrl (github.com), NOT baseUrl (api.github.com)
        return externalUrl + "/" + owner + "/" + repo + ".git";
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    // ── HTTP helpers ─────────────────────────────────────────

    private HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION);
    }

    private JsonNode get(String pathTemplate, Object... args) {
        String path = pathTemplate.formatted(args);
        String url = baseUrl + path;
        try {
            var request = baseRequest(url).GET().build();
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

    private List<JsonNode> getList(String pathTemplate, Object... args) {
        String path = pathTemplate.formatted(args);
        String url = baseUrl + path;
        // Append pagination param
        if (url.contains("?")) {
            url = url + "&per_page=100";
        } else {
            url = url + "?per_page=100";
        }
        return getListPaginated(url);
    }

    private List<JsonNode> getListPaginated(String url) {
        var result = new ArrayList<JsonNode>();
        String nextUrl = url;
        while (nextUrl != null) {
            try {
                var request = baseRequest(nextUrl).GET().build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new RuntimeException(
                        "GitHub API error %d on GET %s: %s".formatted(
                            response.statusCode(),
                            nextUrl,
                            response.body()
                        )
                    );
                }
                var node = mapper.readTree(response.body());
                if (node.isArray()) {
                    for (var item : node) {
                        result.add(item);
                    }
                }
                // Follow Link header pagination
                nextUrl = parseNextLink(response.headers().firstValue("Link").orElse(null));
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("GitHub API request failed: GET " + nextUrl, e);
            }
        }
        return result;
    }

    private JsonNode post(String pathTemplate, Map<String, Object> body, Object... args) {
        String path = pathTemplate.formatted(args);
        String url = baseUrl + path;
        try {
            String json = mapper.writeValueAsString(body);
            var request = baseRequest(url)
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

    private void postVoid(String pathTemplate, Map<String, Object> body, Object... args) {
        String path = pathTemplate.formatted(args);
        String url = baseUrl + path;
        try {
            String json = mapper.writeValueAsString(body);
            var request = baseRequest(url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException(
                    "GitHub API error %d on POST %s: %s".formatted(response.statusCode(), path, response.body())
                );
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("GitHub API request failed: POST " + path, e);
        }
    }

    private static String parseNextLink(String linkHeader) {
        if (linkHeader == null || linkHeader.isBlank()) return null;
        // Link header format: <url>; rel="next", <url>; rel="last"
        for (String part : linkHeader.split(",")) {
            part = part.trim();
            if (part.contains("rel=\"next\"")) {
                int start = part.indexOf('<');
                int end = part.indexOf('>');
                if (start >= 0 && end > start) {
                    return part.substring(start + 1, end);
                }
            }
        }
        return null;
    }

    // ── DTO conversion ───────────────────────────────────────

    private PrData toPrData(JsonNode node) {
        List<String> assignees = new ArrayList<>();
        if (node.has("assignees") && node.get("assignees").isArray()) {
            for (var a : node.get("assignees")) {
                assignees.add(a.path("login").asText(""));
            }
        }
        boolean merged =
            node.path("merged").asBoolean(false) ||
            (!node.path("merged_at").isNull() && !node.path("merged_at").isMissingNode() &&
                !node.path("merged_at").asText("").isBlank());
        return new PrData(
            node.path("number").asInt(),
            node.path("title").asText(""),
            node.path("body").asText(""),
            merged,
            node.path("head").path("ref").asText(""),
            node.path("base").path("ref").asText(""),
            assignees
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
