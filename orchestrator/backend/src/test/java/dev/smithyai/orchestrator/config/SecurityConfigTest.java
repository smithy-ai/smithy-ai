package dev.smithyai.orchestrator.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The security chain as a browser meets it: through the real filter chain, not
 * around it. The dashboard is on a public address, so what is open and what a
 * cross-site page can make a logged-in browser do are the properties that matter.
 */
@SpringBootTest
class SecurityConfigTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter securityFilterChain;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilterChain).build();
    }

    @Test
    void theApiNeedsALogin() throws Exception {
        assertEquals(401, mvc.perform(get("/api/dashboard/runs")).andReturn().getResponse().getStatus());
        assertEquals(200, mvc.perform(get("/api/health")).andReturn().getResponse().getStatus());
    }

    @Test
    void theDashboardPageCarriesTheDefaultSecurityHeaders() throws Exception {
        for (String path : new String[] { "/", "/index.html", "/login" }) {
            var response = mvc.perform(get(path)).andReturn().getResponse();

            assertEquals("DENY", response.getHeader("X-Frame-Options"), path);
            assertEquals("nosniff", response.getHeader("X-Content-Type-Options"), path);
        }
    }

    @Test
    void anyPageHandsTheBrowserACsrfToken() throws Exception {
        // The login form is the first POST the dashboard makes, so the token has
        // to arrive with the page, before anything asks for it.
        var cookie = mvc.perform(get("/login")).andReturn().getResponse().getCookie("XSRF-TOKEN");

        assertNotNull(cookie, "XSRF-TOKEN cookie on the page response");
        assertFalse(cookie.isHttpOnly(), "the dashboard script has to read it");
    }

    @Test
    void aPostWithoutTheTokenIsRefused() throws Exception {
        var response = mvc
            .perform(post("/api/login").param("username", "admin").param("password", "wrong"))
            .andReturn()
            .getResponse();

        assertEquals(403, response.getStatus());
    }

    @Test
    void aPostWithTheTokenReachesAuthentication() throws Exception {
        var token = mvc.perform(get("/login")).andReturn().getResponse().getCookie("XSRF-TOKEN");

        var response = mvc
            .perform(
                post("/api/login")
                    .cookie(new Cookie("XSRF-TOKEN", token.getValue()))
                    .header("X-XSRF-TOKEN", token.getValue())
                    .param("username", "admin")
                    .param("password", "wrong")
            )
            .andReturn()
            .getResponse();

        // Past the CSRF check, rejected on the password.
        assertEquals(401, response.getStatus());
    }

    @Test
    void webhooksNeedNoToken() throws Exception {
        // Providers cannot fetch a CSRF token; their own signature is the check.
        var response = mvc
            .perform(post("/webhooks/no-such-connector").content("{}").contentType("application/json"))
            .andReturn()
            .getResponse();

        assertEquals(404, response.getStatus());
    }
}
