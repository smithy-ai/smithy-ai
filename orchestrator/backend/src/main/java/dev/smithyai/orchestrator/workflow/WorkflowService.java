package dev.smithyai.orchestrator.workflow;

import dev.smithyai.orchestrator.model.events.WorkflowEvent;
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

    public WorkflowService(List<AbstractWorkflowFactory<?>> factories, ContainerService containerService) {
        this.factories = factories;
        this.containerService = containerService;
        log.info("WorkflowService initialized with {} workflow factories", factories.size());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInstances() {
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
                            state.workflowType()
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
                        state.workflowType(),
                        state.stage()
                    );
                }
            } catch (Exception e) {
                log.error("Failed to recover container {}", containerName, e);
            }
        }

        log.info("Recovery complete: {}/{} containers recovered", recovered, containers.size());
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
