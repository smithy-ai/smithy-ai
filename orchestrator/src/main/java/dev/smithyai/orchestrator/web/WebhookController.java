package dev.smithyai.orchestrator.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.workflow.WorkflowService;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
public class WebhookController {

    private final VcsProviderConfig vcsConfig;
    private final WorkflowService workflowService;
    private final ObjectMapper mapper;
    private final EventMapper eventMapper;
    private final GitLabEventMapper gitLabEventMapper;

    public WebhookController(
        VcsProviderConfig vcsConfig,
        WorkflowService workflowService,
        ObjectMapper mapper,
        EventMapper eventMapper,
        @Nullable GitLabEventMapper gitLabEventMapper
    ) {
        this.vcsConfig = vcsConfig;
        this.workflowService = workflowService;
        this.mapper = mapper;
        this.eventMapper = eventMapper;
        this.gitLabEventMapper = gitLabEventMapper;
    }

    @PostMapping("/webhooks/forgejo")
    public ResponseEntity<String> handleWebhook(
        @RequestBody byte[] body,
        @RequestHeader(value = "X-Forgejo-Signature", defaultValue = "") String signature,
        @RequestHeader(value = "X-Forgejo-Event", required = false) String forgejoEvent,
        @RequestHeader(value = "X-Gitea-Event", required = false) String giteaEvent
    ) {
        String secret = vcsConfig.forgejo() != null ? vcsConfig.forgejo().webhookSecret() : "";
        if (!verifySignature(body, signature, secret)) {
            log.warn("Webhook rejected: invalid HMAC signature");
            return ResponseEntity.status(403).body("Invalid signature");
        }

        String eventType = forgejoEvent != null ? forgejoEvent : giteaEvent;
        if (eventType == null || eventType.isBlank()) {
            log.warn("Missing webhook event type header");
            return ResponseEntity.badRequest().body("Missing event type");
        }

        try {
            JsonNode payload = mapper.readTree(body);
            String action = payload.path("action").asText(null);
            log.info("Webhook received: {} (action={})", eventType, action);

            WorkflowEvent event = parse(eventType, action, payload);
            if (event != null) {
                log.debug("Parsed event: {}", event.getClass().getSimpleName());
                workflowService.onEvent(event);
            } else {
                log.debug("No event produced for {} (action={})", eventType, action);
            }
            return ResponseEntity.ok("");
        } catch (Exception e) {
            log.error("Failed to process webhook", e);
            return ResponseEntity.internalServerError().body("Error");
        }
    }

    @PostMapping("/webhooks/gitlab")
    public ResponseEntity<String> handleGitLabWebhook(
        @RequestBody byte[] body,
        @RequestHeader(value = "X-Gitlab-Token", defaultValue = "") String token,
        @RequestHeader(value = "X-Gitlab-Event", required = false) String eventType
    ) {
        if (gitLabEventMapper == null) {
            return ResponseEntity.status(404).body("GitLab integration not enabled");
        }

        String secret = vcsConfig.gitlab() != null ? vcsConfig.gitlab().webhookSecret() : null;
        if (
            secret == null ||
            secret.isBlank() ||
            !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))
        ) {
            log.warn("GitLab webhook rejected: invalid token");
            return ResponseEntity.status(403).body("Invalid token");
        }

        if (eventType == null || eventType.isBlank()) {
            log.warn("Missing GitLab event type header");
            return ResponseEntity.badRequest().body("Missing event type");
        }

        try {
            JsonNode payload = mapper.readTree(body);
            log.info("GitLab webhook received: {}", eventType);

            WorkflowEvent event = gitLabEventMapper.map(eventType, payload);
            if (event != null) {
                log.debug("Parsed GitLab event: {}", event.getClass().getSimpleName());
                workflowService.onEvent(event);
            } else {
                log.debug("No event produced for GitLab {}", eventType);
            }
            return ResponseEntity.ok("");
        } catch (Exception e) {
            log.error("Failed to process GitLab webhook", e);
            return ResponseEntity.internalServerError().body("Error");
        }
    }

    @GetMapping("/api/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("ok");
    }

    // ── Parse ────────────────────────────────────────────────

    private WorkflowEvent parse(String eventType, String action, JsonNode payload) {
        return switch (eventType) {
            case "issues" -> eventMapper.mapIssueEvent(action, payload);
            case "issue_comment" -> eventMapper.mapIssueComment(payload);
            case "push" -> eventMapper.mapPush(payload);
            case "pull_request" -> eventMapper.mapPullRequest(action, payload);
            case "pull_request_comment" -> "reviewed".equals(action)
                ? eventMapper.mapReviewSubmitted(payload)
                : eventMapper.mapPrComment(payload);
            case "pull_request_rejected" -> eventMapper.mapReviewSubmitted(payload);
            case "action_run_failure", "action_run_recover" -> eventMapper.mapCiEvent(eventType, payload);
            default -> {
                log.debug("Unhandled event type: {} (action={})", eventType, action);
                yield null;
            }
        };
    }

    // ── Signature verification ───────────────────────────────

    public static boolean verifySignature(byte[] payload, String signature, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(payload);
            String expectedHex = HexFormat.of().formatHex(expected);
            return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.warn("Webhook signature verification failed due to algorithm/key error", e);
            return false;
        }
    }
}
