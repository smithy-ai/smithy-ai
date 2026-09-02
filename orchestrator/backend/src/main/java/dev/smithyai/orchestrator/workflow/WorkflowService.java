package dev.smithyai.orchestrator.workflow;

import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.engine.RunEngine;
import dev.smithyai.orchestrator.runtime.store.RunRecorder;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import dev.smithyai.orchestrator.service.docker.ContainerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The way in from a webhook.
 *
 * <p>Thin on purpose: an event goes to the engine, which asks every loaded
 * definition what it means. This used to broadcast to every workflow factory
 * and let each opt out with a negative check, which is how one flow's factory
 * ended up importing another's just to bow out of its events.
 */
@Slf4j
@Component
public class WorkflowService {

    private final ContainerService containerService;
    private final RunStore runStore;
    private final RunEngine engine;
    private final IgnoredEventExplainer explainer;

    public WorkflowService(
        ContainerService containerService,
        RunStore runStore,
        RunEngine engine,
        IgnoredEventExplainer explainer
    ) {
        this.containerService = containerService;
        this.runStore = runStore;
        this.engine = engine;
        this.explainer = explainer;
    }

    public void onEvent(WorkflowEvent event) {
        var outcomes = engine.handle(event);
        // A human gesture nothing reacted to gets an explanation, not silence.
        if (explainer != null) explainer.explainIfIgnored(event, outcomes);
    }

    /**
     * On startup, reconcile what the store believes with what Docker has.
     *
     * <p>There is no state to recover — a run's state is in the store, not in
     * its container — but a container can be stopped while its run is still
     * active, so anything an active run holds is started. A run whose container
     * has gone is a real state that used to be invisible, and a container
     * nobody claims is worth knowing about before it is reaped.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcile() {
        try {
            var active = runStore.findActive();
            var containers = new java.util.HashSet<>(containerService.listAllManagedContainers());

            int orphanedRuns = 0;
            for (var run : active) {
                var held = runStore.findEnvironmentNames(run.id(), RunRecorder.CONTAINER);
                held.forEach(containers::remove);
                if (held.isEmpty()) continue;
                for (String name : held) {
                    if (containerService.containerExists(name)) {
                        // Stopped is not gone: start it so the run's next event
                        // does not have to.
                        if (!containerService.ensureRunning(name)) {
                            log.warn("Run {} holds container {}, which will not start", run.id(), name);
                        }
                        continue;
                    }
                    orphanedRuns++;
                    log.warn(
                        "Run {} ({}) is active in state '{}' but its container {} is gone",
                        run.id(),
                        run.workflowName(),
                        run.state(),
                        name
                    );
                    runStore.detachEnvironment(RunRecorder.CONTAINER, name);
                }
            }

            log.info(
                "{} active run(s); {} with a missing container, {} unclaimed container(s): {}",
                active.size(),
                orphanedRuns,
                containers.size(),
                containers
            );
        } catch (RuntimeException e) {
            log.warn("Could not reconcile runs with containers at startup", e);
        }
    }
}
