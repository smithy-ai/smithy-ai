package dev.smithyai.orchestrator.forgejo;

import static org.junit.jupiter.api.Assertions.*;

import dev.smithyai.orchestrator.web.WebhookController;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WebhookVerifierTest {

    @Test
    void validSignature() throws Exception {
        String secret = "test-secret";
        byte[] payload = "hello world".getBytes();

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(payload));

        assertTrue(WebhookController.verifySignature(payload, signature, secret));
    }

    @Test
    void invalidSignature() {
        assertFalse(WebhookController.verifySignature("hello".getBytes(), "invalid", "secret"));
    }

    @Test
    void emptySignature() {
        assertFalse(WebhookController.verifySignature("hello".getBytes(), "", "secret"));
    }
}
