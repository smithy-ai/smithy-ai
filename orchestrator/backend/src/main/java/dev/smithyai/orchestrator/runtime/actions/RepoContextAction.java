package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.config.RepositoryConfigResolver;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Which repository holds the architectural context for another.
 *
 * <p>Read from the repository's own {@code .smithy/config.yml} over the provider
 * API, so a team decides where its guidelines live without anything being
 * configured centrally. A definition needs the answer as data — it clones that
 * repository beside the one under review.
 */
@Slf4j
@Component
public class RepoContextAction implements WorkflowAction {

    private final RepositoryConfigResolver repositoryConfig;
    private final VcsClient vcs;

    public RepoContextAction(RepositoryConfigResolver repositoryConfig, @Qualifier("smithyVcsClient") VcsClient vcs) {
        this.repositoryConfig = repositoryConfig;
        this.vcs = vcs;
    }

    @Override
    public String type() {
        return "repo.context";
    }

    @Override
    public boolean idempotent() {
        return true;
    }

    @Override
    public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
        var contextRepo = repositoryConfig.contextRepository(required(input, "owner"), required(input, "repo"));
        // Whether it actually exists, so a workflow that consults guidelines
        // opportunistically can skip the clone instead of failing on the many
        // repositories that have no context repo at all.
        boolean exists = exists(contextRepo.owner(), contextRepo.repo());
        return Map.of(
            "owner",
            contextRepo.owner(),
            "repo",
            contextRepo.repo(),
            "fullName",
            contextRepo.fullName(),
            "cloneUrl",
            vcs.cloneUrl(contextRepo.owner(), contextRepo.repo()),
            "exists",
            exists
        );
    }

    private boolean exists(String owner, String repo) {
        try {
            return vcs.repoExists(owner, repo);
        } catch (RuntimeException e) {
            // Guidelines are an aid, not a dependency: a provider hiccup here
            // must not stop the work itself.
            log.warn("Could not check whether context repo {}/{} exists; treating it as absent", owner, repo, e);
            return false;
        }
    }
}
