package dev.smithyai.orchestrator.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The dashboard routes on the client, so a deep link or a reload on any of its
 * paths has to serve the app shell rather than 404. Every route the frontend
 * declares needs a match here; anything not listed falls through to the API
 * and static handlers.
 */
@Configuration
public class SpaForwardingConfig implements WebMvcConfigurer {

    private static final String[] SPA_PATHS = {
        "/login",
        "/instances",
        "/runs",
        "/session",
        "/session/**",
        "/logs",
        "/logs/**",
    };

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String path : SPA_PATHS) {
            registry.addViewController(path).setViewName("forward:/index.html");
        }
    }
}
