package dev.smithyai.orchestrator.service.vcs.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.dto.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GitLabClient implements VcsClient, IssueTrackerClient {

    private final String baseUrl;
    private final String externalUrl;
    private final String token;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Map<String, Integer> userIdCache = new ConcurrentHashMap<>();

    public GitLabClient(String baseUrl, String externalUrl, String token) {
        this.baseUrl = baseUrl;
        this.externalUrl = externalUrl != null && !externalUrl.isBlank() ? externalUrl : baseUrl;
        this.token = token;
        this.http = HttpClient.newBuilder().build();
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    // ── IssueTrackerClient ───────────────────────────────────

    @Override
    public IssueData getIssue(String owner, String repo, int number) {
        var node = get("/projects/%s/issues/%d", projectId(owner, repo), number);
        List<String> assignees = new ArrayList<>();
        if (node.has("assignees") && node.get("assignees").isArray()) {
            for (var a : node.get("assignees")) {
                assignees.add(a.path("username").asText(""));
            }
        }
        List<String> labels = new ArrayList<>();
        if (node.has("labels") && node.get("labels").isArray()) {
            for (var l : node.get("labels")) {
                labels.add(l.asText());
            }
        }
        return new IssueData(
            node.path("iid").asInt(),
            node.path("title").asText(""),
            node.path("description").asText(""),
            node.path("state").asText("opened"),
            assignees,
            "", // GitLab issues don't have a branch ref
            labels
        );
    }

    @Override
    public List<CommentEntry> getIssueComments(String owner, String repo, int number) {
        var nodes = getList("/projects/%s/issues/%d/notes?sort=asc", projectId(owner, repo), number);
        var result = new ArrayList<CommentEntry>();
        for (var n : nodes) {
            if (n.path("system").asBoolean(false)) continue; // Skip system notes
            result.add(
                new CommentEntry(
                    n.path("id").asLong(),
                    n.path("author").path("username").asText(""),
                    n.path("body").asText(""),
                    parseDateTime(n.path("created_at").asText(""))
                )
            );
        }
        return result;
    }

    @Override
    public CommentEntry createIssueComment(String owner, String repo, int number, String body) {
        var node = post("/projects/%s/issues/%d/notes", Map.of("body", body), projectId(owner, repo), number);
        return new CommentEntry(
            node.path("id").asLong(),
            node.path("author").path("username").asText(""),
            node.path("body").asText(""),
            parseDateTime(node.path("created_at").asText(""))
        );
    }

    @Override
    public void setIssueAssignees(String owner, String repo, int number, List<String> assignees) {
        List<Integer> ids = resolveUserIds(assignees);
        put("/projects/%s/issues/%d", Map.of("assignee_ids", ids), projectId(owner, repo), number);
    }

    @Override
    public List<AttachmentInfo> getIssueAttachments(String owner, String repo, int number) {
        // GitLab doesn't have a dedicated attachments API for issues.
        // Attachments are embedded as markdown links in issue description/comments.
        // Return empty — attachment support is non-critical for GitLab.
        return List.of();
    }

    @Override
    public List<AttachmentInfo> getCommentAttachments(String owner, String repo, long commentId) {
        return List.of();
    }

    @Override
    public byte[] downloadAttachment(String url) {
        try {
            var request = HttpRequest.newBuilder().uri(URI.create(url)).header("PRIVATE-TOKEN", token).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("GitLab attachment download failed: " + response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("GitLab attachment download failed", e);
        }
    }

    // ── VcsClient: Pull/Merge Requests ───────────────────────

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
        params.put("source_branch", head);
        params.put("target_branch", base);
        params.put("description", body);
        if (draft) {
            params.put("draft", true);
        }
        var node = post("/projects/%s/merge_requests", params, projectId(owner, repo));
        return toPrData(node);
    }

    @Override
    public PrData getPullRequest(String owner, String repo, int number) {
        var node = get("/projects/%s/merge_requests/%d", projectId(owner, repo), number);
        return toPrData(node);
    }

    @Override
    public PrData findPrByHead(String owner, String repo, String head) {
        var nodes = getList(
            "/projects/%s/merge_requests?state=opened&source_branch=%s",
            projectId(owner, repo),
            URLEncoder.encode(head, StandardCharsets.UTF_8)
        );
        if (nodes.isEmpty()) return null;
        return toPrData(nodes.getFirst());
    }

    @Override
    public void createPrComment(String owner, String repo, int prNumber, String body) {
        post("/projects/%s/merge_requests/%d/notes", Map.of("body", body), projectId(owner, repo), prNumber);
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
        String pid = projectId(owner, repo);

        // Post summary as a regular note
        if (body != null && !body.isBlank()) {
            post("/projects/%s/merge_requests/%d/notes", Map.of("body", body), pid, prNumber);
        }

        // Post inline comments as discussions with position
        if (comments != null) {
            for (var c : comments) {
                try {
                    // Get the MR diff information for positioning
                    var diffRefs = getMrDiffRefs(pid, prNumber);
                    var position = new LinkedHashMap<String, Object>();
                    position.put("position_type", "text");
                    position.put("base_sha", diffRefs.baseSha());
                    position.put("head_sha", diffRefs.headSha());
                    position.put("start_sha", diffRefs.startSha());
                    position.put("new_path", c.path());
                    position.put("new_line", c.newPosition());

                    post(
                        "/projects/%s/merge_requests/%d/discussions",
                        Map.of("body", c.body(), "position", position),
                        pid,
                        prNumber
                    );
                } catch (Exception e) {
                    log.warn("Failed to post inline comment on {}: {}", c.path(), e.getMessage());
                    // Fallback: post as regular note
                    String fallbackBody = "**%s:%d** — %s".formatted(c.path(), c.newPosition(), c.body());
                    post("/projects/%s/merge_requests/%d/notes", Map.of("body", fallbackBody), pid, prNumber);
                }
            }
        }
    }

    @Override
    public List<ReviewEntry> getPrReviews(String owner, String repo, int prNumber) {
        // GitLab doesn't have grouped reviews. Approximate by listing MR notes.
        var nodes = getList("/projects/%s/merge_requests/%d/notes?sort=asc", projectId(owner, repo), prNumber);
        var result = new ArrayList<ReviewEntry>();
        for (var n : nodes) {
            if (n.path("system").asBoolean(false)) continue;
            result.add(
                new ReviewEntry(
                    n.path("id").asLong(),
                    n.path("author").path("username").asText(""),
                    n.path("body").asText(""),
                    "", // no review state in GitLab notes
                    "", // no commit_id on regular notes
                    parseDateTime(n.path("created_at").asText(""))
                )
            );
        }
        return result;
    }

    @Override
    public List<ReviewCommentEntry> getReviewComments(String owner, String repo, int prNumber, long reviewId) {
        // In GitLab, reviewId maps to a note ID. Return it as a single-element list.
        try {
            var node = get("/projects/%s/merge_requests/%d/notes/%d", projectId(owner, repo), prNumber, reviewId);
            return List.of(
                new ReviewCommentEntry(
                    node.path("author").path("username").asText(""),
                    node.path("body").asText(""),
                    "", // path not available on regular notes
                    0,
                    parseDateTime(node.path("created_at").asText(""))
                )
            );
        } catch (Exception e) {
            log.warn("Failed to fetch GitLab note {} for MR #{}", reviewId, prNumber, e);
            return List.of();
        }
    }

    @Override
    public LatestReviewResult getLatestReviewComments(String owner, String repo, int prNumber, String reviewer) {
        String pid = projectId(owner, repo);
        var notes = getList("/projects/%s/merge_requests/%d/notes?sort=desc", pid, prNumber);

        // Find the latest regular note by this reviewer (review summary body)
        String reviewBody = "";
        OffsetDateTime reviewNoteDate = null;
        for (var n : notes) {
            if (n.path("system").asBoolean(false)) continue;
            String author = n.path("author").path("username").asText("");
            if (reviewer.equals(author)) {
                reviewBody = n.path("body").asText("");
                reviewNoteDate = parseDateTime(n.path("created_at").asText(""));
                break;
            }
        }

        // Fetch MR discussions to find inline comments by the reviewer
        var discussions = getList("/projects/%s/merge_requests/%d/discussions", pid, prNumber);
        var comments = new ArrayList<ReviewCommentEntry>();
        for (var discussion : discussions) {
            var discussionNotes = discussion.path("notes");
            if (!discussionNotes.isArray() || discussionNotes.isEmpty()) continue;

            // Check first note in discussion — it determines authorship and position
            var firstNote = discussionNotes.get(0);
            if (firstNote.path("system").asBoolean(false)) continue;
            String noteAuthor = firstNote.path("author").path("username").asText("");
            if (!reviewer.equals(noteAuthor)) continue;

            // Skip notes without a position (regular discussion notes, not inline)
            var position = firstNote.path("position");
            if (position.isMissingNode() || position.isNull()) continue;

            // Date filter: only include discussions created on or after the review summary note
            OffsetDateTime noteDate = parseDateTime(firstNote.path("created_at").asText(""));
            if (reviewNoteDate != null && noteDate != null && noteDate.isBefore(reviewNoteDate)) continue;

            String path = position.path("new_path").asText("");
            if (path.isEmpty()) {
                path = position.path("old_path").asText("");
            }
            int line = position.path("new_line").asInt(0);
            if (line == 0) {
                line = position.path("old_line").asInt(0);
            }

            comments.add(new ReviewCommentEntry(noteAuthor, firstNote.path("body").asText(""), path, line, noteDate));
        }

        return new LatestReviewResult(comments, reviewBody);
    }

    // ── VcsClient: Assignees & Reviewers ─────────────────────

    @Override
    public void setPrAssignees(String owner, String repo, int prNumber, List<String> assignees) {
        List<Integer> ids = resolveUserIds(assignees);
        put("/projects/%s/merge_requests/%d", Map.of("assignee_ids", ids), projectId(owner, repo), prNumber);
    }

    @Override
    public void requestReview(String owner, String repo, int prNumber, List<String> reviewers) {
        List<Integer> ids = resolveUserIds(reviewers);
        put("/projects/%s/merge_requests/%d", Map.of("reviewer_ids", ids), projectId(owner, repo), prNumber);
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
            get("/projects/%s", projectId(owner, repo));
            return true;
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) return false;
            throw e;
        }
    }

    // ── VcsClient: URL helpers ───────────────────────────────

    @Override
    public String fileBrowseUrl(String repoHtmlUrl, String branch, String path) {
        return repoHtmlUrl + "/-/blob/" + branch + "/" + path;
    }

    @Override
    public String prUrl(String externalBaseUrl, String owner, String repo, int number) {
        return externalBaseUrl + "/" + owner + "/" + repo + "/-/merge_requests/" + number;
    }

    @Override
    public String cloneUrl(String owner, String repo) {
        return baseUrl + "/" + owner + "/" + repo + ".git";
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    // ── HTTP helpers ─────────────────────────────────────────

    private String projectId(String owner, String repo) {
        return URLEncoder.encode(owner + "/" + repo, StandardCharsets.UTF_8);
    }

    private JsonNode get(String pathTemplate, Object... args) {
        String path = pathTemplate.formatted(args);
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v4" + path))
                .header("PRIVATE-TOKEN", token)
                .GET()
                .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException(
                    "GitLab API error %d on GET %s: %s".formatted(response.statusCode(), path, response.body())
                );
            }
            return mapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("GitLab API request failed: GET " + path, e);
        }
    }

    private List<JsonNode> getList(String pathTemplate, Object... args) {
        var node = get(pathTemplate, args);
        if (node.isArray()) {
            var list = new ArrayList<JsonNode>();
            for (var item : node) {
                list.add(item);
            }
            return list;
        }
        return List.of();
    }

    private JsonNode post(String pathTemplate, Map<String, Object> body, Object... args) {
        String path = pathTemplate.formatted(args);
        try {
            String json = mapper.writeValueAsString(body);
            var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v4" + path))
                .header("PRIVATE-TOKEN", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException(
                    "GitLab API error %d on POST %s: %s".formatted(response.statusCode(), path, response.body())
                );
            }
            return mapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("GitLab API request failed: POST " + path, e);
        }
    }

    private void put(String pathTemplate, Map<String, Object> body, Object... args) {
        String path = pathTemplate.formatted(args);
        try {
            String json = mapper.writeValueAsString(body);
            var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v4" + path))
                .header("PRIVATE-TOKEN", token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException(
                    "GitLab API error %d on PUT %s: %s".formatted(response.statusCode(), path, response.body())
                );
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("GitLab API request failed: PUT " + path, e);
        }
    }

    // ── User ID resolution ───────────────────────────────────

    private List<Integer> resolveUserIds(List<String> usernames) {
        var ids = new ArrayList<Integer>();
        for (String username : usernames) {
            Integer id = userIdCache.computeIfAbsent(username, this::lookupUserId);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private Integer lookupUserId(String username) {
        try {
            var nodes = getList("/users?username=%s", URLEncoder.encode(username, StandardCharsets.UTF_8));
            if (!nodes.isEmpty()) {
                return nodes.getFirst().path("id").asInt();
            }
            log.warn("GitLab user not found: {}", username);
            return null;
        } catch (Exception e) {
            log.warn("Failed to lookup GitLab user {}: {}", username, e.getMessage());
            return null;
        }
    }

    // ── MR diff refs (for inline comments) ───────────────────

    private record DiffRefs(String baseSha, String headSha, String startSha) {}

    private DiffRefs getMrDiffRefs(String pid, int mrIid) {
        var node = get("/projects/%s/merge_requests/%d", pid, mrIid);
        var refs = node.path("diff_refs");
        return new DiffRefs(
            refs.path("base_sha").asText(""),
            refs.path("head_sha").asText(""),
            refs.path("start_sha").asText("")
        );
    }

    // ── DTO conversion ───────────────────────────────────────

    private PrData toPrData(JsonNode node) {
        List<String> assignees = new ArrayList<>();
        if (node.has("assignees") && node.get("assignees").isArray()) {
            for (var a : node.get("assignees")) {
                assignees.add(a.path("username").asText(""));
            }
        }
        return new PrData(
            node.path("iid").asInt(),
            node.path("title").asText(""),
            node.path("description").asText(""),
            "merged".equals(node.path("state").asText("")),
            node.path("source_branch").asText(""),
            node.path("target_branch").asText(""),
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
