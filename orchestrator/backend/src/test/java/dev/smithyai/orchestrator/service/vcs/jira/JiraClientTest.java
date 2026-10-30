package dev.smithyai.orchestrator.service.vcs.jira;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.smithyai.orchestrator.config.VcsProviderConfig.JiraProviderConfig;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JiraClientTest {

    private static final byte[] DESIGN = "<html>design</html>".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> authorizationOnFirstHop = new AtomicReference<>();

    @BeforeEach
    void startJira() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        baseUrl = "http://localhost:" + server.getAddress().getPort();
        // Jira Cloud serves the bytes from a pre-signed media URL and answers the
        // attachment's own URL with a redirect to it.
        server.createContext("/rest/api/2/attachment/content/42", exchange -> {
            authorizationOnFirstHop.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Location", baseUrl + "/media/42");
            exchange.sendResponseHeaders(303, -1);
            exchange.close();
        });
        server.createContext("/media/42", exchange -> {
            exchange.sendResponseHeaders(200, DESIGN.length);
            exchange.getResponseBody().write(DESIGN);
            exchange.close();
        });
        server.createContext("/rest/api/2/attachment/content/404", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopJira() {
        server.stop(0);
    }

    @Test
    void downloadAttachmentFollowsTheCloudRedirectToTheFile() {
        var client = cloudClient();

        byte[] downloaded = client.downloadAttachment(baseUrl + "/rest/api/2/attachment/content/42");

        assertArrayEquals(DESIGN, downloaded);
        assertTrue(
            authorizationOnFirstHop.get().startsWith("Basic "),
            "Jira itself is called with the API credentials"
        );
    }

    @Test
    void downloadAttachmentFailsWhenJiraHasNoSuchFile() {
        var client = cloudClient();

        var failure = assertThrows(RuntimeException.class, () ->
            client.downloadAttachment(baseUrl + "/rest/api/2/attachment/content/404")
        );

        assertTrue(failure.getMessage().contains("404"), failure.getMessage());
    }

    private JiraClient cloudClient() {
        return new JiraClient(
            new JiraProviderConfig(baseUrl, "bot@example.com", "secret", null, null, null, null, null, null, null, null)
        );
    }
}
