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

    @Test
    void getPrReviewsFollowsPaginationLinks() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v3/repos/owner/repo/pulls/5/reviews", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            byte[] response;
            if (query != null && query.contains("page=2")) {
                response = "[{\"id\":2,\"user\":{\"login\":\"bob\"}}]".getBytes(StandardCharsets.UTF_8);
            } else {
                int port = server.getAddress().getPort();
                exchange
                    .getResponseHeaders()
                    .add(
                        "Link",
                        "<http://localhost:" +
                        port +
                        "/api/v3/repos/owner/repo/pulls/5/reviews?per_page=100&page=2>; rel=\"next\""
                    );
                response = "[{\"id\":1,\"user\":{\"login\":\"alice\"}}]".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            var client = new GitHubClient("http://localhost:" + port, "", "token");
            var reviews = client.getPrReviews("owner", "repo", 5);
            assertEquals(2, reviews.size());
            assertEquals("alice", reviews.get(0).userLogin());
            assertEquals("bob", reviews.get(1).userLogin());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getReviewCommentsRequestsFullPagesAndFollowsLinks() throws Exception {
        AtomicReference<String> firstQuery = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v3/repos/owner/repo/pulls/5/reviews/9/comments", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            byte[] response;
            if (query != null && query.contains("page=2")) {
                response = "[{\"body\":\"second\",\"user\":{\"login\":\"bob\"}}]".getBytes(StandardCharsets.UTF_8);
            } else {
                firstQuery.set(query == null ? "" : query);
                int port = server.getAddress().getPort();
                exchange
                    .getResponseHeaders()
                    .add(
                        "Link",
                        "<http://localhost:" +
                        port +
                        "/api/v3/repos/owner/repo/pulls/5/reviews/9/comments?per_page=100&page=2>; rel=\"next\""
                    );
                response = "[{\"body\":\"first\",\"user\":{\"login\":\"alice\"}}]".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            var client = new GitHubClient("http://localhost:" + port, "", "token");
            var comments = client.getReviewComments("owner", "repo", 5, 9);
            assertTrue(firstQuery.get().contains("per_page=100"), "expected per_page=100, got: " + firstQuery.get());
            assertEquals(2, comments.size());
            assertEquals("first", comments.get(0).body());
            assertEquals("second", comments.get(1).body());
        } finally {
            server.stop(0);
        }
    }
}
