package dev.smithyai.orchestrator.workflow;

import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.service.docker.ContainerService;
import dev.smithyai.orchestrator.service.docker.dto.ContainerState;
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
        var containers = containerService.listManagedContainers();
        if (containers.isEmpty()) {
            log.info("No managed containers found for recovery");
            return;
        }

        log.info("Found {} managed containers, attempting recovery", containers.size());
        int recovered = 0;

        for (var containerName : containers) {
            try {
                var stateOpt = containerService.readStateSafe(containerName);
                if (stateOpt.isEmpty()) {
                    log.warn("Skipping recovery of {} — could not read state", containerName);
                    continue;
                }
                var state = stateOpt.get();

                boolean matched = false;
                for (var factory : factories) {
                    if (factory.canRecover(containerName, state)) {
                        recoverFromFactory(factory, containerName, state);
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

    private <T extends AbstractWorkflowInstance> void recoverFromFactory(
        AbstractWorkflowFactory<T> factory,
        String containerName,
        ContainerState state
    ) {
        T instance = factory.recoverInstance(containerName, state);
        factory.registerInstance(containerName, instance);
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
                var instance = factory.getInstance(d.key());
                if (instance != null && instance.exists()) {
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
}
