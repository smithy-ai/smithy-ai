package dev.smithyai.orchestrator.gitlab;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.smithyai.orchestrator.service.vcs.dto.DiscussionEntry;
import dev.smithyai.orchestrator.service.vcs.gitlab.GitLabClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitLabClientTest {

    private static final String DISCUSSIONS = """
        [
          {"id":"d1","notes":[
            {"id":1,"system":false,"resolvable":true,"resolved":true,"author":{"username":"architect-bot"},
             "body":"Name this","created_at":"2026-11-02T10:00:00.000Z",
             "position":{"new_path":"src/App.java","new_line":12}},
            {"id":2,"system":false,"author":{"username":"b.human"},"body":"Done",
             "created_at":"2026-11-02T10:05:00.000Z"}]},
          {"id":"d0","notes":[
            {"id":9,"system":true,"body":"added 1 commit","author":{"username":"b.human"},
             "created_at":"2026-11-02T09:00:00.000Z"}]}
        ]
        """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> rawPath = new AtomicReference<>();
    private final AtomicReference<String> rawQuery = new AtomicReference<>();
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
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            token.set(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = rawPath.get().startsWith("/api/v4/projects/acme%2Fapp/merge_requests/41/discussions")
                ? DISCUSSIONS
                : "{}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
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

    @Test
    void discussionsKeepThreadsWithPositionsAndDropSystemOnlyOnes() {
        var discussions = client.getPrDiscussions("acme", "app", 41);

        assertEquals(1, discussions.size());
        DiscussionEntry thread = discussions.get(0);
        assertEquals("d1", thread.id());
        assertTrue(thread.resolvable());
        assertTrue(thread.resolved());
        assertEquals(2, thread.notes().size());
        var opening = thread.notes().get(0);
        assertEquals("architect-bot", opening.userLogin());
        assertEquals("src/App.java", opening.path());
        assertEquals(12, opening.line());
        var reply = thread.notes().get(1);
        assertEquals("", reply.path());
        assertEquals(0, reply.line());
        assertTrue(rawQuery.get().contains("per_page=100"));
    }
}
