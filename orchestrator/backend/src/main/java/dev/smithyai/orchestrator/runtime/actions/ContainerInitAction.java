package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.config.Actors;
import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.service.docker.dto.ContainerConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Give the run a container to work in.
 *
 * <p>Creating one is expensive — it clones the repository and waits for the
 * image's init to finish — so a run that already holds one keeps it. That also
 * makes the step safe to replay: a transition interrupted after the clone
 * resumes into the same working tree rather than starting over.
 */
@Slf4j
@Component
public class ContainerInitAction implements WorkflowAction {

    private final RunEnvironments environments;
    private final DockerConfig dockerConfig;
    private final Actors actors;

    public ContainerInitAction(RunEnvironments environments, DockerConfig dockerConfig, Actors actors) {
        this.environments = environments;
        this.dockerConfig = dockerConfig;
        this.actors = actors;
    }

    @Override
    public String type() {
        return "container.init";
    }

    @Override
    public Set<Capability> requires() {
        return Set.of(Capability.ENVIRONMENT);
    }

    @Override
    public boolean idempotent() {
        // Guarded by the run's own environment record rather than by replay
        // bookkeeping, so it is also correct when reached from a later state.
        return true;
    }

    @Override
    public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
        var run = context.run();
        var existing = environments.findContainer(run);
        if (existing.isPresent()) {
            return Map.of("name", existing.get().getContainerName(), "created", false);
        }

        String name = required(input, "name");
        var config = ContainerConfig.builder()
            .cloneUrl(required(input, "cloneUrl"))
            // Empty is meaningful: smithy-init then clones the remote's
            // default branch, which is what a workspace that only reads wants.
            .branch(optional(input, "branch", ""))
            .sourceBranch(optional(input, "sourceBranch", null))
            .cacheVolumes(dockerConfig.getCacheVolumeMap())
            // The workflow's own actor unless the step says otherwise, so a
            // container the architect works in pushes as the architect.
            .gitEmail(optional(input, "gitEmail", actors.email(context.actor())))
            .gitUsername(optional(input, "gitUsername", null))
            .vcsToken(optional(input, "vcsToken", actors.token(context.actor())))
            .extraRepos(extraRepos(input))
            .workflow(run.workflowName())
            .build();

        environments.createContainer(run, name, config, optional(input, "stage", run.state()));
        return Map.of("name", name, "created", true);
    }

    /**
     * Repositories cloned alongside the working one — a design-system or
     * guidelines repo the agent reads. Each entry is {@code cloneUrl}, {@code
     * path} and an optional {@code branch}.
     */
    @SuppressWarnings("unchecked")
    private List<ContainerConfig.ExtraRepo> extraRepos(Map<String, Object> input) {
        Object raw = input.get("extraRepos");
        if (!(raw instanceof List<?> entries)) return List.of();
        var repos = new ArrayList<ContainerConfig.ExtraRepo>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("container.init extraRepos entries must be maps, got: " + entry);
            }
            var fields = (Map<String, Object>) map;
            String cloneUrl = optional(fields, "cloneUrl", "");
            if (cloneUrl.isBlank()) {
                // Nothing to clone. A workflow can list a repository that only
                // some events have — a story raised in a tracker with no
                // repository behind it — without a condition it cannot express
                // on a list entry.
                log.debug("container.init: skipping extra repo with no clone URL at {}", fields.get("path"));
                continue;
            }
            repos.add(
                new ContainerConfig.ExtraRepo(
                    cloneUrl,
                    required(fields, "path"),
                    // Empty, never null: the entry is serialized with List.of,
                    // which rejects nulls, and that failure used to be swallowed.
                    optional(fields, "branch", "")
                )
            );
        }
        return repos;
    }
}
