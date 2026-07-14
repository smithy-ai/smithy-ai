package dev.smithyai.orchestrator.service.claude;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.hubspot.jinjava.Jinjava;
import com.hubspot.jinjava.JinjavaConfig;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Map;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

@Component
public class PromptRenderer {

    private final Jinjava jinjava;
    private final ResourceLoader resourceLoader;

    public PromptRenderer(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
        this.jinjava = new Jinjava(JinjavaConfig.newBuilder().build());
    }

    public String render(String templateName, Map<String, Object> variables) {
        String template = loadTemplate(templateName);
        return jinjava.render(template, variables);
    }

    private String loadTemplate(String templateName) {
        // Try filesystem first (Docker mount at /prompts), then classpath
        for (String location : new String[] { "file:/prompts/" + templateName, "classpath:prompts/" + templateName }) {
            var resource = resourceLoader.getResource(location);
            if (resource.exists()) {
                try (Reader reader = new InputStreamReader(resource.getInputStream(), UTF_8)) {
                    return FileCopyUtils.copyToString(reader);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read template: " + location, e);
                }
            }
        }
        throw new RuntimeException("Template not found: " + templateName);
    }
}
