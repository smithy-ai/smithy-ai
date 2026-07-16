package dev.smithyai.orchestrator.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WebhookControllerTest {

    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);

    private static String hmacHex(String secret, byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload));
    }

    private static WebhookController githubController(String webhookSecret) {
        var github = new VcsProviderConfig.GitHubProviderConfig("", "", webhookSecret, "smithy-token", null);
        var vcsConfig = new VcsProviderConfig("github", null, null, null, github);
        var botConfig = new BotConfig(
            new BotConfig.BotEntry("smithy-bot", "smithy@example.com"),
            new BotConfig.BotEntry("architect-bot", "architect@example.com")
        );
        var mapper = new GitHubEventMapper(botConfig, vcsConfig, null);
        return new WebhookController(vcsConfig, null, new ObjectMapper(), null, null, mapper);
    }

    private static WebhookController forgejoController(String webhookSecret) {
        var forgejo = new VcsProviderConfig.ForgejoProviderConfig(
            "http://forgejo.local",
            "",
            webhookSecret,
            "smithy-token",
            null
        );
        var vcsConfig = new VcsProviderConfig("forgejo", null, forgejo, null, null);
        return new WebhookController(vcsConfig, null, new ObjectMapper(), null, null, null);
    }

    @Test
    void githubWebhookRejectedWhenSecretIsNull() {
        var controller = githubController(null);
        var response = controller.handleGitHubWebhook(BODY, "sha256=deadbeef", "ping");
        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void githubWebhookRejectedWhenSecretIsBlank() {
        var controller = githubController("");
        var response = controller.handleGitHubWebhook(BODY, "sha256=deadbeef", "ping");
        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void githubWebhookAcceptsValidSignature() throws Exception {
        var controller = githubController("s3cret");
        String signature = "sha256=" + hmacHex("s3cret", BODY);
        var response = controller.handleGitHubWebhook(BODY, signature, "ping");
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void forgejoWebhookRejectedWhenSecretNotConfigured() {
        var controller = forgejoController("");
        var response = controller.handleWebhook(BODY, "deadbeef", "ping", null);
        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void forgejoWebhookAcceptsValidSignature() throws Exception {
        var controller = forgejoController("s3cret");
        String signature = hmacHex("s3cret", BODY);
        var response = controller.handleWebhook(BODY, signature, "ping", null);
        assertEquals(200, response.getStatusCode().value());
    }
}
