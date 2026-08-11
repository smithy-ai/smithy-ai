package dev.smithyai.orchestrator.runtime.engine;

import dev.smithyai.orchestrator.config.WorkflowPolicyConfig;
import dev.smithyai.orchestrator.runtime.actions.Capability;
import dev.smithyai.orchestrator.runtime.definition.LoadedWorkflowDefinition;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinition;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionException;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionLoader;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * The definitions this orchestrator can run, checked at startup.
 *
 * <p>Checking here is the point: a definition asking for something the
 * configured provider cannot do — deleting a file on a provider with no
 * delete API, creating issues on a tracker that only comments — used to fail
 * at the moment the step ran, which in practice meant mid-flight on someone
 * else's work. It fails at startup now, naming the action and the capability.
 *
 * <p>A definition that fails validation is dropped rather than taking the
 * process down with it, so one bad workflow does not stop the others running.
 */
@Slf4j
@Component
public class WorkflowRegistry {

    private final WorkflowDefinitionLoader loader;
    private final CapabilityValidator validator;
    private final WorkflowPolicyConfig policy;
    private final Set<Capability> supported;

    private final Map<String, LoadedWorkflowDefinition> byName = new LinkedHashMap<>();

    public WorkflowRegistry(
        WorkflowDefinitionLoader loader,
        CapabilityValidator validator,
        WorkflowPolicyConfig policy,
        @Qualifier("smithyVcsClient") VcsClient vcs,
        @Qualifier("smithyIssueTracker") IssueTrackerClient issues
    ) {
        this.loader = loader;
        this.validator = validator;
        this.policy = policy;
        // The platform always supplies an environment and an agent; everything
        // else depends on which provider is configured.
        var capabilities = EnumSet.of(Capability.ENVIRONMENT, Capability.AGENT);
        capabilities.addAll(vcs.capabilities());
        capabilities.addAll(issues.capabilities());
        this.supported = Set.copyOf(capabilities);
    }

    @PostConstruct
    public void loadAll() {
        byName.clear();
        for (var loaded : loader.load(Path.of(policy.resolvedDefinitionsDir()))) {
            String name = loaded.definition().metadata().name();
            try {
                validator.validate(loaded.source(), loaded.definition(), supported);
            } catch (WorkflowDefinitionException e) {
                log.error("Workflow '{}' from {} is not runnable here: {}", name, loaded.source(), e.getMessage());
                continue;
            }
            var replaced = byName.put(name, loaded);
            if (replaced != null) {
                log.info("Workflow '{}' from {} overrides {}", name, loaded.source(), replaced.source());
            }
        }
        log.info("{} workflow definition(s) available: {}", byName.size(), byName.keySet());
    }

    public Optional<WorkflowDefinition> find(String name) {
        return Optional.ofNullable(byName.get(name)).map(LoadedWorkflowDefinition::definition);
    }

    public WorkflowDefinition require(String name) {
        return find(name).orElseThrow(() ->
            new IllegalStateException("No workflow definition named '%s' (have: %s)".formatted(name, byName.keySet()))
        );
    }

    public List<WorkflowDefinition> all() {
        return byName.values().stream().map(LoadedWorkflowDefinition::definition).toList();
    }

    public Set<Capability> supportedCapabilities() {
        return supported;
    }
}
