package dev.smithyai.orchestrator.github;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.smithyai.orchestrator.service.vcs.dto.InlineComment;
import dev.smithyai.orchestrator.service.vcs.github.GitHubClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GitHubClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void inlineOnlyCommentReviewIncludesBody() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v3/repos/owner/repo/pulls/7/reviews", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            var client = new GitHubClient("http://localhost:" + port, "", "token");

            client.createPullReview(
                "owner",
                "repo",
                7,
                "",
                "COMMENT",
                List.of(new InlineComment("src/App.java", "Fix this", 42))
            );
        } finally {
            server.stop(0);
        }

        var body = mapper.readTree(requestBody.get());
        assertEquals("COMMENT", body.path("event").asText());
        assertEquals("Inline review comments.", body.path("body").asText());
        assertEquals("src/App.java", body.path("comments").get(0).path("path").asText());
        assertEquals(42, body.path("comments").get(0).path("line").asInt());
    }
}
