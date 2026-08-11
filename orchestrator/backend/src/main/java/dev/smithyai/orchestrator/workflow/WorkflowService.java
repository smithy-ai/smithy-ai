package dev.smithyai.orchestrator.workflow;

import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.store.RunRecorder;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import dev.smithyai.orchestrator.service.docker.ContainerService;
import dev.smithyai.orchestrator.workflow.shared.AbstractWorkflowFactory;
import dev.smithyai.orchestrator.workflow.shared.AbstractWorkflowInstance;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WorkflowService {

    private final List<AbstractWorkflowFactory<?>> factories;
    private final ContainerService containerService;
    private final RunStore runStore;

    public WorkflowService(
        List<AbstractWorkflowFactory<?>> factories,
        ContainerService containerService,
        RunStore runStore
    ) {
        this.factories = factories;
        this.containerService = containerService;
        this.runStore = runStore;
        log.info("WorkflowService initialized with {} workflow factories", factories.size());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInstances() {
        logOrphanedRuns();

        var containers = containerService.listAllManagedContainers();
        if (containers.isEmpty()) {
            log.info("No managed containers found for recovery");
            return;
        }

        log.info("Found {} managed containers, attempting recovery", containers.size());
        int recovered = 0;

        for (var containerName : containers) {
            try {
                if (!containerService.ensureRunning(containerName)) {
                    log.warn("Skipping recovery of {} — container could not be started", containerName);
                    continue;
                }
                var stateOpt = containerService.readStateSafe(containerName);
                if (stateOpt.isEmpty()) {
                    log.warn("Skipping recovery of {} — could not read state", containerName);
                    continue;
                }
                var state = stateOpt.get();

                boolean matched = false;
                for (var factory : factories) {
                    if (factory.canRecover(containerName, state)) {
                        factory.getOrRecoverInstance(containerName, state);
                        log.info(
                            "Recovered instance {} (stage={}, workflow={})",
                            containerName,
                            state.stage(),
                            state.workflow()
                        );
                        recovered++;
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    log.warn(
                        "No factory matched container {} (workflow={}, stage={})",
                        containerName,
                        state.workflow(),
                        state.stage()
                    );
                }
            } catch (Exception e) {
                log.error("Failed to recover container {}", containerName, e);
            }
        }

        log.info("Recovery complete: {}/{} containers recovered", recovered, containers.size());
    }

    /**
     * Report runs the store still considers active whose container is gone.
     * Container recovery is still driven by {@code docker ps} — the store does
     * not own the lifecycle yet — but a run with no container is a real state
     * that used to be invisible, so surface it rather than let it sit silently.
     */
    private void logOrphanedRuns() {
        try {
            var active = runStore.findActive();
            if (active.isEmpty()) return;

            var orphaned = active
                .stream()
                .filter(run -> runStore.findEnvironmentNames(run.id(), RunRecorder.CONTAINER).isEmpty())
                .toList();
            log.info("Run store: {} active run(s), {} with no container attached", active.size(), orphaned.size());
            orphaned.forEach(run ->
                log.warn(
                    "Run {} ({}) is active in state '{}' but holds no container",
                    run.id(),
                    run.workflowName(),
                    run.state()
                )
            );
        } catch (RuntimeException e) {
            log.warn("Could not read the run store during recovery", e);
        }
    }

    public void onEvent(WorkflowEvent event) {
        for (var type : factories) {
            var action = type.decideEventAction(event);
            executeEventAction(type, action, event);
        }
    }

    private void executeEventAction(AbstractWorkflowFactory<?> factory, EventAction action, WorkflowEvent event) {
        String factoryName = factory.getClass().getSimpleName();
        switch (action) {
            case EventAction.Create c -> {
                log.debug("[{}] Create instance for key={}", factoryName, c.key());
                var instance = factory.getOrCreateInstance(c.key(), event);
                instance.onEvent(event);
            }
            case EventAction.Dispatch d -> {
                AbstractWorkflowInstance instance = factory.getInstance(d.key());
                if (instance == null) {
                    instance = recoverOnDemand(factory, d.key());
                }
                if (instance == null) {
                    // Container is gone entirely — let the factory rebuild from the event
                    instance = factory.getOrResurrectInstance(d.key(), event);
                    if (instance != null) {
                        log.info(
                            "[{}] Resurrected instance key={} for {}",
                            factoryName,
                            d.key(),
                            event.getClass().getSimpleName()
                        );
                        instance.onEvent(event);
                        return;
                    }
                }
                // A registered instance whose container was removed is still dispatched:
                // handlers either recreate the container or ignore the event.
                if (instance != null && (containerService.ensureRunning(d.key()) || !instance.exists())) {
                    log.debug(
                        "[{}] Dispatch event {} to key={}",
                        factoryName,
                        event.getClass().getSimpleName(),
                        d.key()
                    );
                    instance.onEvent(event);
                } else {
                    log.debug("[{}] No active instance for key={}, ignoring", factoryName, d.key());
                }
            }
            case EventAction.Destroy d -> {
                var instance = factory.removeInstance(d.key());
                if (instance != null) {
                    log.debug("[{}] Destroy instance key={}", factoryName, d.key());
                    instance.destroy();
                    log.info("Destroyed instance {}", d.key());
                } else {
                    log.debug("[{}] Destroy requested but no instance for key={}", factoryName, d.key());
                }
            }
            case EventAction.Ignore ignored -> {
                log.debug("[{}] Ignoring event {}", factoryName, event.getClass().getSimpleName());
            }
        }
    }

    /**
     * Attempt to recover an instance from its (possibly stopped) container when
     * an event arrives for a key with no registered instance — e.g. after an
     * orchestrator redeploy that missed the startup recovery pass.
     */
    private AbstractWorkflowInstance recoverOnDemand(AbstractWorkflowFactory<?> factory, String key) {
        if (!containerService.isManagedContainer(key)) {
            return null;
        }
        if (!containerService.ensureRunning(key)) {
            log.warn("Cannot lazily recover {} — container could not be started", key);
            return null;
        }
        var stateOpt = containerService.readStateSafe(key);
        if (stateOpt.isEmpty()) {
            log.warn("Cannot lazily recover {} — could not read state", key);
            return null;
        }
        var instance = factory.getOrRecoverInstance(key, stateOpt.get());
        if (instance != null) {
            log.info("Lazily recovered instance {} (stage={}) on incoming event", key, stateOpt.get().stage());
        }
        return instance;
    }
}
