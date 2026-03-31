package dev.smithyai.knowledgebase.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

@Slf4j
@Configuration
public class ConfigLoader {

    private static final Path DEFAULT_OVERRIDE_PATH = Path.of("/config/knowledgebase.yml");

    private final KnowledgebaseConfig config;

    public ConfigLoader(Environment env) {
        this.config = loadConfig(env);
        log.info("Loaded knowledgebase config (git={})", config.git().isConfigured());
    }

    @Bean
    public KnowledgebaseConfig knowledgebaseConfig() {
        return config;
    }

    @Bean
    public KnowledgebaseConfig.GitConfig gitConfig() {
        return config.git();
    }

    @Bean
    public KnowledgebaseConfig.VectorstoreConfig vectorstoreConfig() {
        return config.vectorstore();
    }

    @Bean
    public KnowledgebaseConfig.WebhookConfig webhookConfig() {
        return config.webhook();
    }

    @Bean
    public KnowledgebaseConfig.OpenaiConfig openaiConfig() {
        return config.openai();
    }

    @SuppressWarnings("unchecked")
    private static KnowledgebaseConfig loadConfig(Environment env) {
        var resources = new ArrayList<Resource>();

        resources.add(new ClassPathResource("knowledgebase.yml"));
        log.info("Loading base config from classpath:knowledgebase.yml");

        String configPath = env.getProperty("KNOWLEDGEBASE_CONFIG", env.getProperty("config", (String) null));
        if (configPath != null) {
            Path path = Path.of(configPath);
            if (Files.isReadable(path)) {
                log.info("Merging override config from: {}", path);
                resources.add(new FileSystemResource(path));
            }
        } else if (Files.isReadable(DEFAULT_OVERRIDE_PATH)) {
            log.info("Merging override config from: {}", DEFAULT_OVERRIDE_PATH);
            resources.add(new FileSystemResource(DEFAULT_OVERRIDE_PATH));
        }

        var factory = new YamlPropertiesFactoryBean();
        factory.setResources(resources.toArray(new Resource[0]));
        Properties props = factory.getObject();

        props.replaceAll((k, v) -> env.resolvePlaceholders((String) v));

        var propertySources = new MutablePropertySources();
        propertySources.addFirst(new MapPropertySource("knowledgebase", (Map) props));
        var binder = new Binder(ConfigurationPropertySources.from(propertySources));
        return binder.bindOrCreate("", KnowledgebaseConfig.class);
    }
}
