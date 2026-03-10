package dev.smithyai.orchestrator.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Slf4j
@Configuration
public class ConfigLoader {

    private final SmithyConfig config;

    public ConfigLoader(Environment env) {
        String raw = loadRawYaml(env);
        String resolved = env.resolveRequiredPlaceholders(raw);
        try {
            var mapper = YAMLMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
            this.config = mapper.readValue(resolved, SmithyConfig.class);
            config.vcs().validate();
            log.info("Loaded orchestrator config (provider={})", config.vcs().resolvedProvider());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse orchestrator config", e);
        }
    }

    @Bean
    public DockerConfig dockerConfig() {
        return config.docker();
    }

    @Bean
    public ClaudeConfig claudeConfig() {
        return config.claude();
    }

    @Bean
    public VcsProviderConfig vcsProviderConfig() {
        return config.vcs();
    }

    @Bean
    public BotConfig botConfig() {
        return config.bots();
    }

    private static String loadRawYaml(Environment env) {
        // 1. Check for explicit config path via env var or CLI arg
        String configPath = env.getProperty("ORCHESTRATOR_CONFIG", env.getProperty("config", (String) null));
        if (configPath != null) {
            Path path = Path.of(configPath);
            if (Files.isReadable(path)) {
                log.info("Loading orchestrator config from: {}", path);
                try {
                    return Files.readString(path);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read config: " + path, e);
                }
            }
        }

        // 2. Check default external path
        Path defaultExternal = Path.of("/config/orchestrator.yml");
        if (Files.isReadable(defaultExternal)) {
            log.info("Loading orchestrator config from: {}", defaultExternal);
            try {
                return Files.readString(defaultExternal);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read config: " + defaultExternal, e);
            }
        }

        // 3. Fall back to classpath
        log.info("Loading orchestrator config from classpath:orchestrator.yml");
        try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream("orchestrator.yml")) {
            if (is == null) {
                throw new IllegalStateException(
                    "No orchestrator.yml found on classpath or at /config/orchestrator.yml"
                );
            }
            return new String(is.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read classpath:orchestrator.yml", e);
        }
    }
}
