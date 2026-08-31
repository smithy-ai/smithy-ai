package dev.smithyai.orchestrator.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.smithyai.orchestrator.service.claude.ClaudeSession;
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

    private final OrchestratorConfig config;
    private final Environment environment;

    public ConfigLoader(Environment env) {
        this.environment = env;
        String raw = loadRawYaml(env);
        try {
            var mapper = YAMLMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
            this.config = mapper.readValue(raw, OrchestratorConfig.class);
            OrchestratorConfigValidator.validate(config, env);
            ClaudeConfig claude = resolvedClaudeConfig();
            claude.validate();
            ClaudeSession.configureDefaultModel(claude.resolvedModel());
            config.agent().claude().resolvedTurnTimeout().ifPresent(ClaudeSession::configureTurnTimeout);
            log.info(
                "Loaded orchestrator config (connectors={}, defaultVcs={}, model={}, turnTimeout={})",
                config.connectors().keySet(),
                config.defaults().vcs(),
                claude.resolvedModel(),
                ClaudeSession.turnTimeout()
            );
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse orchestrator config", e);
        }
    }

    @Bean
    public OrchestratorConfig orchestratorConfig() {
        return config;
    }

    @Bean
    public StorageConfig storageConfig() {
        return config.storage() == null ? StorageConfig.defaults() : config.storage();
    }

    @Bean
    public AuthConfig authConfig() {
        return config.auth() == null ? AuthConfig.defaults() : config.auth();
    }

    @Bean
    public DockerConfig dockerConfig() {
        var docker = config.runtime() == null ? null : config.runtime().docker();
        return new DockerConfig(
            value(docker == null ? null : docker.command(), "docker"),
            value(docker == null ? null : docker.network(), "smithy-net"),
            value(docker == null ? null : docker.taskImage(), "claude-task-default:latest"),
            docker == null ? "" : String.join(",", docker.caches())
        );
    }

    @Bean
    public ClaudeConfig claudeConfig() {
        return resolvedClaudeConfig();
    }

    /** The unresolved agent block, for the settings that are not secrets. */
    @Bean
    public AgentConfig.ClaudeAgentConfig claudeAgentConfig() {
        return config.agent().claude();
    }

    private ClaudeConfig resolvedClaudeConfig() {
        var claude = config.agent().claude();
        return new ClaudeConfig(
            resolveSecret(claude.oauthToken(), "agent.claude.oauthToken"),
            resolveSecret(claude.apiKey(), "agent.claude.apiKey"),
            claude.model()
        );
    }

    @Bean
    public KnowledgebaseConfig knowledgebaseConfig() {
        return config.knowledgebase() != null ? config.knowledgebase() : new KnowledgebaseConfig(false, null, null);
    }

    @Bean
    public CiConfig ciConfig() {
        return config.ci() != null ? config.ci() : new CiConfig(null);
    }

    @Bean
    public WorkflowPolicyConfig workflowPolicyConfig() {
        var workflows = config.workflows();
        var defaults = workflows == null ? null : workflows.defaults();
        return new WorkflowPolicyConfig(
            defaults == null ? null : defaults.planApprovedLabel(),
            defaults == null ? null : defaults.branchPrefix(),
            workflows == null ? null : workflows.definitionsDir()
        );
    }

    @Bean
    public WorkflowConfig workflowConfig() {
        return config.workflows() != null ? config.workflows() : new WorkflowConfig(null, true, null);
    }

    public String resolveSecret(SecretRef secret, String field) {
        return secret == null ? "" : secret.resolve(environment, field);
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String loadRawYaml(Environment env) {
        // 1. Check for explicit config path via env var or CLI arg
        String configPath = env.getProperty("ORCHESTRATOR_CONFIG", env.getProperty("config", (String) null));
        if (configPath != null) {
            Path path = Path.of(configPath);
            if (!Files.isReadable(path)) {
                throw new IllegalStateException("Configured orchestrator config is not readable: " + path);
            }
            log.info("Loading orchestrator config from: {}", path);
            try {
                return Files.readString(path);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read config: " + path, e);
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
