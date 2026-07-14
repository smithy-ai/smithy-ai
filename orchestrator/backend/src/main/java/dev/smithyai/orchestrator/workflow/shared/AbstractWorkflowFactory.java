package dev.smithyai.orchestrator.workflow.shared;

import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.service.docker.dto.ContainerState;
import dev.smithyai.orchestrator.workflow.EventAction;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractWorkflowFactory<T extends AbstractWorkflowInstance> {

    protected final ConcurrentHashMap<String, T> instances = new ConcurrentHashMap<>();
    private final ReentrantLock createLock = new ReentrantLock();

    /**
     * Determine routing action for an event.
     * Returns {@link EventAction#IGNORE} for events this workflow type doesn't handle.
     */
    public abstract EventAction decideEventAction(WorkflowEvent event);

    /**
     * Get an existing instance or create a new one under lock to prevent races.
     */
    public T getOrCreateInstance(String key, WorkflowEvent event) {
        createLock.lock();
        try {
            var existing = instances.get(key);
            if (existing != null) return existing;
            var instance = createInstance(key, event);
            instances.put(key, instance);
            return instance;
        } finally {
            createLock.unlock();
        }
    }

    protected abstract T createInstance(String key, WorkflowEvent event);

    /**
     * Whether this factory can recover a workflow instance from the given container and state.
     */
    public abstract boolean canRecover(String containerName, ContainerState state);

    /**
     * Reconstruct a workflow instance from an existing container's state.
     */
    public abstract T recoverInstance(String containerName, ContainerState state);

    // ── Registry API ────────────────────────────────────────

    public T getInstance(String key) {
        return instances.get(key);
    }

    public void registerInstance(String key, T instance) {
        instances.put(key, instance);
    }

    public T removeInstance(String key) {
        return instances.remove(key);
    }

    public boolean hasInstance(String key) {
        return instances.containsKey(key);
    }

    public Map<String, T> allInstances() {
        return Collections.unmodifiableMap(instances);
    }
}
