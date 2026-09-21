package dev.smithyai.orchestrator.config;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Security headers as a real servlet container sends them. MockMvc showed them on
 * the dashboard page while the deployed server did not: with the default lazy
 * header writing, every static resource went out without them. Only a real
 * server reproduces that.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityHeadersServerTest {

    @LocalServerPort
    private int port;

    @Test
    void staticPagesCarryTheDefaultSecurityHeaders() throws Exception {
        var client = HttpClient.newHttpClient();
        for (String path : new String[] { "/", "/index.html", "/login" }) {
            var response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.discarding()
            );

            assertEquals(200, response.statusCode(), path);
            assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElse(null), path);
            assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null), path);
            // The login form is the dashboard's first POST, so the token has to
            // arrive with the page.
            assertTrue(
                response.headers().allValues("Set-Cookie").stream().anyMatch(c -> c.startsWith("XSRF-TOKEN=")),
                path + " sets XSRF-TOKEN"
            );
        }
    }

    @Test
    void aRequestTheProxyReceivedOverHttpsIsTreatedAsSecure() throws Exception {
        // Caddy terminates TLS and says so in X-Forwarded-Proto. Trusting that is
        // what makes the session cookie Secure and turns HSTS on.
        var response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/dashboard/runs"))
                .header("X-Forwarded-Proto", "https")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding()
        );

        assertTrue(response.headers().firstValue("Strict-Transport-Security").isPresent(), "HSTS");
        var session = response
            .headers()
            .allValues("Set-Cookie")
            .stream()
            .filter(c -> c.startsWith("JSESSIONID="))
            .findFirst()
            .orElseThrow();
        assertTrue(session.contains("Secure"), session);
    }
}
