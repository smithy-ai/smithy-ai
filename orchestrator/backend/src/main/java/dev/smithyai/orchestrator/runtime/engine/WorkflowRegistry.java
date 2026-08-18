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
import org.springframework.beans.factory.annotation.Autowired;
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
    private final dev.smithyai.orchestrator.config.OrchestratorConfig orchestratorConfig;

    private final Map<String, LoadedWorkflowDefinition> byName = new LinkedHashMap<>();

    @Autowired
    public WorkflowRegistry(
        WorkflowDefinitionLoader loader,
        CapabilityValidator validator,
        WorkflowPolicyConfig policy,
        @Qualifier("smithyVcsClient") VcsClient vcs,
        @Qualifier("smithyIssueTracker") IssueTrackerClient issues,
        @Qualifier("repoIssueTracker") IssueTrackerClient repoIssues,
        dev.smithyai.orchestrator.config.OrchestratorConfig orchestratorConfig
    ) {
        this.loader = loader;
        this.validator = validator;
        this.policy = policy;
        this.orchestratorConfig = orchestratorConfig;
        this.supported = supportedCapabilities(vcs, issues, repoIssues);
    }

    public WorkflowRegistry(
        WorkflowDefinitionLoader loader,
        CapabilityValidator validator,
        WorkflowPolicyConfig policy,
        VcsClient vcs,
        IssueTrackerClient issues,
        IssueTrackerClient repoIssues
    ) {
        this(loader, validator, policy, vcs, issues, repoIssues, null);
    }

    private static Set<Capability> supportedCapabilities(
        VcsClient vcs,
        IssueTrackerClient issues,
        IssueTrackerClient repoIssues
    ) {
        // The platform always supplies an environment and an agent; everything
        // else depends on which providers are configured.
        //
        // A union across them, which is exact when one system does everything
        // and optimistic when they are split: a definition that creates issues
        // in the story tracker passes validation on the strength of the VCS
        // being able to create them. A step names the tracker it means, so the
        // gap is narrow, but it is real — capabilities are not yet per-provider.
        var capabilities = EnumSet.of(Capability.ENVIRONMENT, Capability.AGENT);
        capabilities.addAll(vcs.capabilities());
        capabilities.addAll(issues.capabilities());
        capabilities.addAll(repoIssues.capabilities());
        return Set.copyOf(capabilities);
    }

    @PostConstruct
    public void loadAll() {
        byName.clear();
        var raw = new LinkedHashMap<String, LoadedWorkflowDefinition>();
        for (var loaded : loader.load(Path.of(policy.resolvedDefinitionsDir()))) {
            var replaced = raw.put(loaded.definition().metadata().name(), loaded);
            if (replaced != null) {
                log.info(
                    "Workflow '{}' from {} overrides {}",
                    loaded.definition().metadata().name(),
                    loaded.source(),
                    replaced.source()
                );
            }
        }

        // A workflow that extends another shadows it: the base is a template to
        // be configured, not something to run alongside its configured form.
        // Without this an operator's catalog-carrying coordinator and the empty
        // built-in it extends would both claim the same story.
        var shadowed = raw
            .values()
            .stream()
            .map(loaded -> loaded.definition().metadata().extendsWorkflow())
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());

        for (var loaded : raw.values()) {
            String name = loaded.definition().metadata().name();
            if (shadowed.contains(name)) {
                log.info("Workflow '{}' is a base for another workflow and will not run on its own", name);
                continue;
            }
            LoadedWorkflowDefinition resolved;
            try {
                resolved = resolveRepositoryCatalog(resolveExtends(loaded, raw));
                requireConfiguredActor(resolved);
                validator.validate(resolved.source(), resolved.definition(), supported);
            } catch (WorkflowDefinitionException e) {
                log.error("Workflow '{}' from {} is not runnable here: {}", name, loaded.source(), e.getMessage());
                continue;
            }
            byName.put(name, resolved);
        }
        log.info("{} workflow definition(s) available: {}", byName.size(), byName.keySet());
    }

    /**
     * Resolve {@code metadata.extends}.
     *
     * <p>Deliberately narrow: the child takes the parent's routing, states and
     * composite actions wholesale and contributes only variables. That is what
     * configuring a shipped workflow needs — a coordinator's repository catalog,
     * a different bot user — and it means "extends" cannot quietly change what
     * a workflow does, only what it is pointed at.
     */
    private LoadedWorkflowDefinition resolveExtends(
        LoadedWorkflowDefinition loaded,
        Map<String, LoadedWorkflowDefinition> available
    ) {
        String parentName = loaded.definition().metadata().extendsWorkflow();
        if (parentName == null || parentName.isBlank()) return loaded;

        var parent = available.get(parentName);
        if (parent == null) {
            throw new WorkflowDefinitionException(
                "extends '%s', which is not among the loaded workflows %s".formatted(parentName, available.keySet())
            );
        }
        if (parent.definition().metadata().extendsWorkflow() != null) {
            throw new WorkflowDefinitionException(
                "extends '%s', which extends something itself — one level only".formatted(parentName)
            );
        }

        var vars = new LinkedHashMap<String, Object>(parent.definition().vars());
        vars.putAll(loaded.definition().vars());
        var base = parent.definition();
        var merged = new WorkflowDefinition(
            base.apiVersion(),
            base.kind(),
            loaded.definition().metadata(),
            base.defaults(),
            vars,
            base.routing(),
            base.state(),
            base.actions()
        );
        log.info("Workflow '{}' extends '{}'", loaded.definition().metadata().name(), parentName);
        return new LoadedWorkflowDefinition(loaded.source() + " (extends " + parentName + ")", merged);
    }

    /**
     * A workflow acts as an identity, and one this deployment has not
     * configured is not an accident to discover on the first event: it means
     * the workflow cannot run here at all. Refused for the same reason a
     * missing capability is, so it stays out of the registry rather than
     * failing once per event with a token it was never given.
     */
    private void requireConfiguredActor(LoadedWorkflowDefinition loaded) {
        if (orchestratorConfig == null) return;
        Object declared = loaded.definition().vars().get("actor");
        if (declared == null) return;
        String actor = String.valueOf(declared);
        // Any connector, not the default one: a deployment may keep the
        // reviewer on the system it reviews and nowhere else.
        boolean configured = orchestratorConfig
            .connectors()
            .values()
            .stream()
            .anyMatch(connector -> connector.actors().containsKey(actor));
        if (!configured) {
            throw new WorkflowDefinitionException(
                "acts as '%s', which no connector configures an identity for".formatted(actor)
            );
        }
    }

    private LoadedWorkflowDefinition resolveRepositoryCatalog(LoadedWorkflowDefinition loaded) {
        Object reference = loaded.definition().vars().get("repositoryCatalog");
        if (reference == null) return loaded;
        String name = String.valueOf(reference);
        if (orchestratorConfig == null) {
            throw new WorkflowDefinitionException(
                "references repository catalog '" + name + "' without deployment config"
            );
        }
        var entries = orchestratorConfig.repositoryCatalogs().get(name);
        if (entries == null) {
            throw new WorkflowDefinitionException(
                "references unknown repository catalog '%s' (available: %s)".formatted(
                    name,
                    orchestratorConfig.repositoryCatalogs().keySet()
                )
            );
        }
        var catalog = entries
            .stream()
            .map(entry -> {
                var item = new LinkedHashMap<String, Object>();
                item.put("source", entry.source());
                item.put("sourceProvider", orchestratorConfig.connectors().get(entry.source()).resolvedProvider());
                item.put("owner", entry.owner());
                item.put("repo", entry.repo());
                item.put("description", entry.description() == null ? "" : entry.description());
                return (Object) item;
            })
            .toList();
        var definition = loaded.definition();
        var vars = new LinkedHashMap<String, Object>(definition.vars());
        vars.put("catalog", catalog);
        var resolved = new WorkflowDefinition(
            definition.apiVersion(),
            definition.kind(),
            definition.metadata(),
            definition.defaults(),
            vars,
            definition.routing(),
            definition.state(),
            definition.actions()
        );
        return new LoadedWorkflowDefinition(loaded.source() + " (catalog " + name + ")", resolved);
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

    /**
     * Whether a definition this registry did not load could run here.
     *
     * <p>Repository-owned definitions arrive on the event path rather than at
     * startup, so they are checked the same way but at the moment they are
     * first seen — a repository with a workflow this provider cannot support
     * loses that workflow, and says so, rather than failing mid-transition.
     */
    public boolean runnable(WorkflowDefinition definition) {
        try {
            // Resolve extends first: a repository-owned definition that only
            // supplies vars has no state of its own, and validating it unresolved
            // fails on a null rather than on anything a maintainer can act on.
            var resolved = resolveExtends(
                new LoadedWorkflowDefinition(definition.metadata().name(), definition),
                byName
            );
            validator.validate(resolved.definition().metadata().name(), resolved.definition(), supported);
            return true;
        } catch (RuntimeException e) {
            // Broad on purpose: this runs on the event path, and one repository's
            // broken workflow must not stop that repository being worked on.
            log.warn("Repository workflow '{}' cannot run here: {}", definition.metadata().name(), e.getMessage());
            return false;
        }
    }

    /** A repository-owned definition, with any {@code extends} already applied. */
    public WorkflowDefinition resolved(WorkflowDefinition definition) {
        try {
            return resolveExtends(
                new LoadedWorkflowDefinition(definition.metadata().name(), definition),
                byName
            ).definition();
        } catch (RuntimeException e) {
            return definition;
        }
    }
}
