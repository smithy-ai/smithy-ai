package dev.smithyai.knowledgebase.web;

import dev.smithyai.knowledgebase.config.KnowledgebaseConfig;
import dev.smithyai.knowledgebase.service.git.GitSyncService;
import dev.smithyai.knowledgebase.service.index.IndexingService;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Slf4j
public class WebhookController {

    private final GitSyncService gitSyncService;
    private final IndexingService indexingService;
    private final String webhookSecret;

    public WebhookController(
        GitSyncService gitSyncService,
        IndexingService indexingService,
        KnowledgebaseConfig.WebhookConfig webhookConfig
    ) {
        this.gitSyncService = gitSyncService;
        this.indexingService = indexingService;
        this.webhookSecret = webhookConfig.secret();
    }

    @PostMapping("/webhooks/push")
    public ResponseEntity<Map<String, Object>> handlePush(
        @RequestBody byte[] payload,
        @RequestHeader(value = "X-Forgejo-Signature", required = false) String forgejoSig,
        @RequestHeader(value = "X-Gitea-Signature", required = false) String giteaSig,
        @RequestHeader(value = "X-Hub-Signature-256", required = false) String hubSig,
        @RequestHeader(value = "X-Gitlab-Token", required = false) String gitlabToken
    ) {
        // Verify webhook signature if secret is configured
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            String hmacSig = forgejoSig != null ? forgejoSig : giteaSig != null ? giteaSig : null;

            if (hmacSig != null) {
                if (!verifyHmacSignature(payload, hmacSig, webhookSecret)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Invalid signature"));
                }
            } else if (hubSig != null) {
                // GitHub uses "sha256=<hex>" format
                String hex = hubSig.startsWith("sha256=") ? hubSig.substring(7) : hubSig;
                if (!verifyHmacSignature(payload, hex, webhookSecret)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Invalid signature"));
                }
            } else if (gitlabToken != null) {
                if (!MessageDigest.isEqual(
                    webhookSecret.getBytes(StandardCharsets.UTF_8),
                    gitlabToken.getBytes(StandardCharsets.UTF_8)
                )) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Invalid token"));
                }
            } else {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Missing webhook signature"));
            }
        }

        if (!gitSyncService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Git repository not configured"));
        }

        if (indexingService.isIndexing()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Sync already in progress"));
        }

        try {
            log.info("Push webhook received, starting sync");

            String commitHash = gitSyncService.syncRepository();
            log.info("Git sync completed. Commit: {}", commitHash);

            Map<String, Object> indexResult = indexingService.buildAndSwapIndex();
            log.info("Index build completed: {}", indexResult);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("commitHash", commitHash);
            response.put("collectionName", indexResult.get("collectionName"));
            response.put("documentCount", indexResult.get("documentCount"));
            response.put("version", indexResult.get("version"));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Sync operation failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("status", "error", "error", e.getMessage()));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "up");
        status.put("initialized", indexingService.isInitialized());
        status.put("indexing", indexingService.isIndexing());
        status.put("activeCollection", indexingService.getActiveCollectionName());
        status.put("gitConfigured", gitSyncService.isConfigured());
        return ResponseEntity.ok(status);
    }

    static boolean verifyHmacSignature(byte[] payload, String signature, String secret) {
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
            return false;
        }
    }
}
