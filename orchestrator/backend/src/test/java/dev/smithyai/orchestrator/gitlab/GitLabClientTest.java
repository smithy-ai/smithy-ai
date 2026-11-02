package dev.smithyai.orchestrator.gitlab;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.smithyai.orchestrator.service.vcs.gitlab.GitLabClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitLabClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> rawPath = new AtomicReference<>();
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private HttpServer server;
    private GitLabClient client;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        // Registered at the root: the server matches contexts on the decoded path,
        // so the encoded project id is asserted on the raw path instead.
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            rawPath.set(exchange.getRequestURI().getRawPath());
            token.set(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        client = new GitLabClient("http://localhost:" + server.getAddress().getPort(), "", "token", false);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void resolvingADiscussionPutsResolvedTrueToItsEndpoint() throws Exception {
        client.resolvePrDiscussion("acme", "app", 41, "d9");

        assertEquals("PUT", method.get());
        assertEquals("/api/v4/projects/acme%2Fapp/merge_requests/41/discussions/d9", rawPath.get());
        assertEquals("token", token.get());
        assertTrue(mapper.readTree(requestBody.get()).path("resolved").asBoolean());
    }
}
