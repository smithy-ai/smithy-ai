package dev.smithyai.orchestrator.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RepositoryConfigResolver {

    private static final Duration CACHE_TTL = Duration.ofMinutes(1);

    private final VcsClient vcsClient;
    private final YAMLMapper mapper;
    private final ConcurrentMap<String, CachedConfig> cache = new ConcurrentHashMap<>();

    public RepositoryConfigResolver(@Qualifier("smithyVcs") VcsClient vcsClient) {
        this.vcsClient = vcsClient;
        this.mapper = YAMLMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    public ContextRepository contextRepository(RepoInfo info) {
        return contextRepository(info.owner(), info.repo());
    }

    public ContextRepository contextRepository(String owner, String repo) {
        return config(owner, repo).contextRepository(owner, repo);
    }

    RepositoryConfig config(String owner, String repo) {
        String key = owner + "/" + repo;
        Instant now = Instant.now();
        var cached = cache.get(key);
        if (cached != null && cached.loadedAt.plus(CACHE_TTL).isAfter(now)) {
            return cached.config;
        }

        RepositoryConfig loaded = load(owner, repo);
        cache.put(key, new CachedConfig(loaded, now));
        return loaded;
    }

    private RepositoryConfig load(String owner, String repo) {
        try {
            var raw = vcsClient.readRepositoryFile(owner, repo, RepositoryConfig.PATH, null);
            if (raw.isEmpty() || raw.get().isBlank()) {
                return RepositoryConfig.empty();
            }
            return mapper.readValue(raw.get(), RepositoryConfig.class);
        } catch (IOException e) {
            log.warn("Failed to parse {}/{}:{}", owner, repo, RepositoryConfig.PATH, e);
            return RepositoryConfig.empty();
        } catch (Exception e) {
            log.warn("Failed to load {}/{}:{}", owner, repo, RepositoryConfig.PATH, e);
            return RepositoryConfig.empty();
        }
    }

    private record CachedConfig(RepositoryConfig config, Instant loadedAt) {}
}
