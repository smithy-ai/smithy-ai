package dev.smithyai.orchestrator.service.vcs.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.smithyai.orchestrator.config.VcsProviderConfig.JiraProviderConfig;
import dev.smithyai.orchestrator.runtime.actions.Capability;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.dto.AttachmentInfo;
import dev.smithyai.orchestrator.service.vcs.dto.CommentEntry;
import dev.smithyai.orchestrator.service.vcs.dto.IssueData;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Jira as an issue tracker. Issue refs are Jira keys ("ECD-4309"); the
 * owner/repo parameters of IssueTrackerClient are ignored, since Jira issues
 * are not repository-scoped. Uses the v2 REST API, which is identical on
 * Cloud and Server/DC and accepts plain-text comment bodies (no ADF).
 */
@Slf4j
public class JiraClient implements IssueTrackerClient {

    /**
     * Jira works as a parent-story tracker: it comments and assigns, but the
     * work itself lives in repositories, so nothing here creates issues.
     */
    @Override
    public java.util.Set<Capability> capabilities() {
        return java.util.EnumSet.of(Capability.ISSUE_COMMENT, Capability.ISSUE_ASSIGN);
    }

    private final String baseUrl;
    private final String botAccountId;
    private final String authHeaderValue;
    private final boolean cloud;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public JiraClient(JiraProviderConfig config) {
        this.baseUrl = config.url().replaceAll("/+$", "");
        this.botAccountId = config.botAccountId();
        this.cloud = config.isCloud();
        if (config.isCloud()) {
            String credentials = config.email() + ":" + config.apiToken();
            this.authHeaderValue =
                "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        } else {
            this.authHeaderValue = "Bearer " + config.apiToken();
        }
        this.http = HttpClient.newBuilder().build();
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    public String botAccountId() {
        return botAccountId;
    }

    // ── IssueTrackerClient ───────────────────────────────────

    @Override
    public IssueData getIssue(String owner, String repo, String issueRef) {
        var node = get("/issue/%s?fields=summary,description,status,assignee,labels,attachment", issueRef);
        var fields = node.path("fields");

        List<String> assignees = new ArrayList<>();
        var assignee = fields.path("assignee");
        if (!assignee.isMissingNode() && !assignee.isNull()) {
            assignees.add(assignee.path("accountId").asText(assignee.path("name").asText("")));
        }
        List<String> labels = new ArrayList<>();
        if (fields.path("labels").isArray()) {
            for (var l : fields.path("labels")) labels.add(l.asText());
        }
        return new IssueData(
            node.path("key").asText(issueRef),
            fields.path("summary").asText(""),
            fields.path("description").asText(""),
            fields.path("status").path("name").asText(""),
            assignees,
            "", // Jira issues carry no branch ref; base branch comes from config/plan
            labels
        );
    }

    @Override
    public List<CommentEntry> getIssueComments(String owner, String repo, String issueRef) {
        var node = get("/issue/%s/comment?orderBy=created", issueRef);
        var result = new ArrayList<CommentEntry>();
        for (var c : node.path("comments")) {
            result.add(
                new CommentEntry(
                    c.path("id").asLong(),
                    c.path("author").path("accountId").asText(c.path("author").path("name").asText("")),
                    c.path("body").asText(""),
                    parseDateTime(c.path("created").asText(""))
                )
            );
        }
        return result;
    }

    @Override
    public CommentEntry createIssueComment(String owner, String repo, String issueRef, String body) {
        var node = post("/issue/%s/comment", Map.of("body", body), issueRef);
        return new CommentEntry(
            node.path("id").asLong(),
            node.path("author").path("accountId").asText(""),
            node.path("body").asText(""),
            parseDateTime(node.path("created").asText(""))
        );
    }

    /**
     * Assigns the issue to the FIRST entry, which must be a Jira accountId
     * (Cloud) or username (Server). An empty list unassigns.
     */
    @Override
    public void setIssueAssignees(String owner, String repo, String issueRef, List<String> assignees) {
        String assignee = assignees.isEmpty() ? null : assignees.getFirst();
        var body = new java.util.HashMap<String, Object>();
        body.put(cloud ? "accountId" : "name", assignee);
        put("/issue/%s/assignee", body, issueRef);
    }

    @Override
    public List<AttachmentInfo> getIssueAttachments(String owner, String repo, String issueRef) {
        var node = get("/issue/%s?fields=attachment", issueRef);
        var result = new ArrayList<AttachmentInfo>();
        for (var a : node.path("fields").path("attachment")) {
            result.add(
                new AttachmentInfo(a.path("id").asLong(), a.path("filename").asText(""), a.path("content").asText(""))
            );
        }
        return result;
    }

    @Override
    public List<AttachmentInfo> getCommentAttachments(String owner, String repo, long commentId) {
        // Jira attachments are issue-level; comment-embedded refs resolve to issue attachments.
        return List.of();
    }

    @Override
    public byte[] downloadAttachment(String url) {
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeaderValue)
                .GET()
                .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("Jira attachment download failed: " + response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Jira attachment download failed: " + url, e);
        }
    }

    /**
     * The issue's remote links: what "Link issue → Add web link" writes, plus
     * whatever an integration chose to record the same way.
     *
     * <p>Not everything attached to a story lives here. A design added through
     * the Figma app's Designs panel is held by that app and exposed by no public
     * Jira API, so it cannot be read at all — its URL has to be in the
     * description, a comment, or a web link to be seen.
     */
    @Override
    public List<String> getIssueLinks(String owner, String repo, String issueRef) {
        var node = get("/issue/%s/remotelink", issueRef);
        var urls = new ArrayList<String>();
        for (var link : node) {
            String url = link.path("object").path("url").asText("");
            if (!url.isBlank()) urls.add(url);
        }
        return urls;
    }

    // ── Jira-specific operations ─────────────────────────────

    /** Adds a label to an issue without touching existing labels. */
    public void addLabel(String issueRef, String label) {
        put("/issue/%s", Map.of("update", Map.of("labels", List.of(Map.of("add", label)))), issueRef);
    }

    /** Transitions an issue to the named status, if such a transition is available. */
    public void transitionTo(String issueRef, String statusName) {
        var node = get("/issue/%s/transitions", issueRef);
        for (var t : node.path("transitions")) {
            if (statusName.equalsIgnoreCase(t.path("to").path("name").asText(""))) {
                post("/issue/%s/transitions", Map.of("transition", Map.of("id", t.path("id").asText())), issueRef);
                return;
            }
        }
        log.warn("No transition to status '{}' available on {}", statusName, issueRef);
    }

    // ── HTTP helpers ─────────────────────────────────────────

    private JsonNode get(String pathTemplate, Object... args) {
        String path = pathTemplate.formatted(args);
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/rest/api/2" + path))
                .header("Authorization", authHeaderValue)
                .GET()
                .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException(
                    "Jira API error %d on GET %s: %s".formatted(response.statusCode(), path, response.body())
                );
            }
            return mapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Jira API request failed: GET " + path, e);
        }
    }

    private JsonNode post(String pathTemplate, Map<String, Object> body, Object... args) {
        return send("POST", pathTemplate, body, args);
    }

    private JsonNode put(String pathTemplate, Map<String, Object> body, Object... args) {
        return send("PUT", pathTemplate, body, args);
    }

    private JsonNode send(String method, String pathTemplate, Map<String, Object> body, Object... args) {
        String path = pathTemplate.formatted(args);
        try {
            String json = mapper.writeValueAsString(body);
            var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/rest/api/2" + path))
                .header("Authorization", authHeaderValue)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json))
                .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException(
                    "Jira API error %d on %s %s: %s".formatted(response.statusCode(), method, path, response.body())
                );
            }
            if (response.body() == null || response.body().isBlank()) {
                return mapper.createObjectNode();
            }
            return mapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Jira API request failed: %s %s".formatted(method, path), e);
        }
    }

    private static OffsetDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            // Jira uses "2026-07-29T10:15:30.000+0200" (no colon in offset)
            try {
                return OffsetDateTime.parse(value.replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2"));
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
