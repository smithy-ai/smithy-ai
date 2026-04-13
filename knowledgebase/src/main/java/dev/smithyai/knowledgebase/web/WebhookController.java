package dev.smithyai.knowledgebase.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.knowledgebase.config.KnowledgebaseConfig;
import dev.smithyai.knowledgebase.config.KnowledgebaseConfig.RepositoryConfig;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GitSyncService gitSyncService;
    private final IndexingService indexingService;
    private final KnowledgebaseConfig config;
    private final String webhookSecret;

    public WebhookController(
        GitSyncService gitSyncService,
        IndexingService indexingService,
        KnowledgebaseConfig config,
        KnowledgebaseConfig.WebhookConfig webhookConfig
    ) {
        this.gitSyncService = gitSyncService;
        this.indexingService = indexingService;
        this.config = config;
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
        // Verify signature
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            String hmacSig = forgejoSig != null ? forgejoSig : giteaSig != null ? giteaSig : null;

            if (hmacSig != null) {
                if (!verifyHmacSignature(payload, hmacSig, webhookSecret)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Invalid signature"));
                }
            } else if (hubSig != null) {
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

        // Parse repo full name from payload (e.g. "owner/repo")
        String repoFullName = parseRepoFullName(payload);
        if (repoFullName == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Could not determine repository name from payload"));
        }

        RepositoryConfig repo = config.findRepository(repoFullName);
        if (repo == null) {
            log.debug("Ignoring push for unconfigured repo: {}", repoFullName);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Repository not configured: " + repoFullName));
        }

        if (indexingService.isIndexing(repoFullName)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Sync already in progress for " + repoFullName));
        }

        try {
            log.info("Push webhook received for repo {}, starting sync", repoFullName);

            String commitHash = gitSyncService.syncRepository(repo);
            log.info("Git sync completed for {}. Commit: {}", repoFullName, commitHash);

            Map<String, Object> indexResult = indexingService.buildAndSwapIndex(repo);
            log.info("Index build completed for {}: {}", repoFullName, indexResult);

            Map<String, Object> response = new HashMap<>(indexResult);
            response.put("status", "success");
            response.put("commitHash", commitHash);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Sync failed for repo {}: {}", repoFullName, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("status", "error", "error", e.getMessage()));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "up");
        status.put("collections", indexingService.allActiveCollections());
        int repoCount = config.repositories() != null ? config.repositories().size() : 0;
        status.put("configuredRepositories", repoCount);
        return ResponseEntity.ok(status);
    }

    private String parseRepoFullName(byte[] payload) {
        try {
            JsonNode root = MAPPER.readTree(payload);

            // Forgejo/Gitea/GitHub: repository.full_name (e.g. "owner/repo")
            JsonNode fullNameNode = root.path("repository").path("full_name");
            if (!fullNameNode.isMissingNode()) {
                return fullNameNode.asText();
            }

            // GitLab: project.path_with_namespace (e.g. "group/repo")
            JsonNode projectNode = root.path("project").path("path_with_namespace");
            if (!projectNode.isMissingNode()) {
                return projectNode.asText();
            }
        } catch (Exception e) {
            log.warn("Failed to parse repo name from webhook payload: {}", e.getMessage());
        }
        return null;
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
