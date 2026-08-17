package dev.smithyai.orchestrator.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.ConnectorRegistry;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class WebhookController {

    private final ConnectorRegistry connectors;
    private final ConnectorEventMappers eventMappers;
    private final WorkflowService workflowService;
    private final ObjectMapper mapper;

    public WebhookController(
        ConnectorRegistry connectors,
        ConnectorEventMappers eventMappers,
        WorkflowService workflowService,
        ObjectMapper mapper
    ) {
        this.connectors = connectors;
        this.eventMappers = eventMappers;
        this.workflowService = workflowService;
        this.mapper = mapper;
    }

    @PostMapping("/webhooks/{connectorId}")
    public ResponseEntity<String> handleWebhook(
        @PathVariable String connectorId,
        @RequestBody byte[] body,
        @RequestHeader HttpHeaders headers,
        @RequestParam(value = "token", defaultValue = "") String queryToken
    ) {
        final String provider;
        try {
            provider = connectors.provider(connectorId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body("Unknown connector");
        }

        String eventType = eventType(provider, headers);
        if (!verify(provider, connectorId, body, headers, queryToken)) {
            log.warn("{} webhook rejected for connector {}: invalid signature", provider, connectorId);
            return ResponseEntity.status(403).body("Invalid signature");
        }
        if (!"jira".equals(provider) && (eventType == null || eventType.isBlank())) {
            return ResponseEntity.badRequest().body("Missing event type");
        }

        try {
            JsonNode payload = mapper.readTree(body);
            WorkflowEvent event = eventMappers.map(connectorId, eventType, payload);
            if (event != null) workflowService.onEvent(event);
            return ResponseEntity.ok("");
        } catch (Exception e) {
            log.error("Failed to process {} webhook for connector {}", provider, connectorId, e);
            return ResponseEntity.internalServerError().body("Error");
        }
    }

    @GetMapping("/api/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("ok");
    }

    private boolean verify(String provider, String connectorId, byte[] body, HttpHeaders headers, String queryToken) {
        String secret = connectors.webhookSecret(connectorId);
        return switch (provider) {
            case "forgejo" -> verifySignature(body, header(headers, "X-Forgejo-Signature"), secret);
            case "github" -> {
                String signature = header(headers, "X-Hub-Signature-256");
                yield verifySignature(
                    body,
                    signature.startsWith("sha256=") ? signature.substring(7) : signature,
                    secret
                );
            }
            case "gitlab" -> constantTimeEquals(secret, header(headers, "X-Gitlab-Token"));
            case "jira" -> {
                String headerToken = header(headers, "X-Jira-Token");
                yield constantTimeEquals(secret, headerToken.isBlank() ? queryToken : headerToken);
            }
            default -> false;
        };
    }

    private static String eventType(String provider, HttpHeaders headers) {
        return switch (provider) {
            case "forgejo" -> {
                String forgejo = header(headers, "X-Forgejo-Event");
                yield forgejo.isBlank() ? header(headers, "X-Gitea-Event") : forgejo;
            }
            case "gitlab" -> header(headers, "X-Gitlab-Event");
            case "github" -> header(headers, "X-GitHub-Event");
            default -> "";
        };
    }

    private static String header(HttpHeaders headers, String name) {
        String value = headers.getFirst(name);
        return value == null ? "" : value;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return (
            expected != null &&
            !expected.isBlank() &&
            MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))
        );
    }

    public static boolean verifySignature(byte[] payload, String signature, String secret) {
        if (secret == null || secret.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(payload));
            return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.warn("Webhook signature verification failed due to algorithm/key error", e);
            return false;
        }
    }
}
