package dev.smithyai.orchestrator.runtime.definition;

import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Workflow definitions a repository owns.
 *
 * <p>A team that wants its own flow should not have to get a file onto the
 * orchestrator's disk. Definitions under {@code .smithy/workflows/} are read
 * over the provider API — before any container exists, like the per-repository
 * config beside them — so a repository can carry its own workflow the same way
 * it carries its CI.
 *
 * <p>Read on the event path, so results are cached briefly and every failure is
 * soft: a repository with a broken definition loses that definition, not its
 * ability to be worked on. Only definitions this provider can actually run are
 * returned, checked the same way the built-ins are at startup.
 */
@Slf4j
@Component
public class RepositoryWorkflowLoader {

    /** Where a repository keeps its own workflows. */
    public static final String PATH = ".smithy/workflows";

    private static final Duration CACHE_TTL = Duration.ofMinutes(1);

    private final VcsClient vcs;
    private final WorkflowDefinitionParser parser;
    private final ConcurrentMap<String, Cached> cache = new ConcurrentHashMap<>();

    public RepositoryWorkflowLoader(@Qualifier("smithyVcsClient") VcsClient vcs, WorkflowDefinitionParser parser) {
        this.vcs = vcs;
        this.parser = parser;
    }

    public List<LoadedWorkflowDefinition> forRepository(RepoInfo info) {
        if (info == null) return List.of();
        String key = info.owner() + "/" + info.repo();
        var now = Instant.now();
        var cached = cache.get(key);
        if (cached != null && cached.loadedAt.plus(CACHE_TTL).isAfter(now)) {
            return cached.definitions;
        }

        var loaded = load(info.owner(), info.repo());
        cache.put(key, new Cached(loaded, now));
        return loaded;
    }

    private List<LoadedWorkflowDefinition> load(String owner, String repo) {
        List<String> paths;
        try {
            paths = vcs.listRepositoryFiles(owner, repo, PATH, null);
        } catch (RuntimeException e) {
            // Includes providers with no file-listing API at all: repository-owned
            // workflows are simply unavailable there, which is not an error here.
            log.debug("Cannot list {} in {}/{}: {}", PATH, owner, repo, e.getMessage());
            return List.of();
        }

        var definitions = new ArrayList<LoadedWorkflowDefinition>();
        for (String path : paths) {
            if (!path.endsWith(".yml") && !path.endsWith(".yaml")) continue;
            String source = "%s/%s:%s".formatted(owner, repo, path);
            try {
                var raw = vcs.readRepositoryFile(owner, repo, path, null);
                if (raw.isEmpty() || raw.get().isBlank()) continue;
                definitions.add(new LoadedWorkflowDefinition(source, parser.parse(source, raw.get())));
            } catch (WorkflowDefinitionException e) {
                log.warn("Ignoring repository workflow {}: {}", source, e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Could not read repository workflow {}", source, e);
            }
        }
        if (!definitions.isEmpty()) {
            log.info("{}/{} carries {} workflow definition(s)", owner, repo, definitions.size());
        }
        return List.copyOf(definitions);
    }

    /** Drop what is cached for a repository — its definitions changed. */
    public void forget(String owner, String repo) {
        cache.remove(owner + "/" + repo);
    }

    private record Cached(List<LoadedWorkflowDefinition> definitions, Instant loadedAt) {}
}
