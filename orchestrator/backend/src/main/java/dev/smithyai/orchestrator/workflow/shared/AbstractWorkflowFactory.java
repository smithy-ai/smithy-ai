package dev.smithyai.orchestrator.workflow.shared;

import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.store.RunRecorder;
import dev.smithyai.orchestrator.runtime.store.RunStatus;
import dev.smithyai.orchestrator.service.docker.dto.ContainerState;
import dev.smithyai.orchestrator.workflow.EventAction;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
public abstract class AbstractWorkflowFactory<T extends AbstractWorkflowInstance> {

    protected final ConcurrentHashMap<String, T> instances = new ConcurrentHashMap<>();
    private final ReentrantLock createLock = new ReentrantLock();

    /**
     * Field-injected rather than threaded through every subclass constructor:
     * this is a cross-cutting concern on a class hierarchy the workflow engine
     * is going to replace, so the churn would be thrown away.
     */
    @Autowired
    protected RunRecorder runs;

    /** The workflow name recorded in the run store, e.g. "smithy-development". */
    protected abstract String workflowName();

    /** The state a freshly created run starts in. */
    protected abstract String initialState();

    /**
     * Bind an instance to a durable run, opening one if this container has none.
     * Called for every path that produces an instance — create, recover and
     * resurrect — so a restart re-attaches instead of forking a second run.
     */
    private T track(String key, T instance, WorkflowEvent event) {
        if (instance == null) return null;
        try {
            instance.bindRun(runs, runs.openRun(workflowName(), key, initialState(), event));
        } catch (RuntimeException e) {
            // A run-store problem must not stop work that is otherwise fine.
            log.error("Failed to open a run for {}; continuing untracked", key, e);
        }
        return instance;
    }

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
            var instance = track(key, createInstance(key, event), event);
            instances.put(key, instance);
            return instance;
        } finally {
            createLock.unlock();
        }
    }

    protected abstract T createInstance(String key, WorkflowEvent event);

    /**
     * Get an existing instance or recover one from a live container's state,
     * under the create lock so concurrent events cannot recover the same
     * container twice. Returns null if this factory cannot recover it.
     */
    public T getOrRecoverInstance(String key, ContainerState state) {
        createLock.lock();
        try {
            var existing = instances.get(key);
            if (existing != null) return existing;
            if (!canRecover(key, state)) return null;
            var instance = track(key, recoverInstance(key, state), null);
            instances.put(key, instance);
            return instance;
        } finally {
            createLock.unlock();
        }
    }

    /**
     * Get an existing instance or resurrect one from scratch for an event whose
     * container no longer exists (e.g. it was destroyed or manually removed),
     * under the create lock. Returns null if this factory cannot resurrect
     * an instance for the given event.
     */
    public T getOrResurrectInstance(String key, WorkflowEvent event) {
        createLock.lock();
        try {
            var existing = instances.get(key);
            if (existing != null) return existing;
            var instance = resurrectInstance(key, event);
            if (instance != null) {
                instances.put(key, track(key, instance, event));
            }
            return instance;
        } finally {
            createLock.unlock();
        }
    }

    /**
     * Recreate a workflow instance from scratch for an event whose container no
     * longer exists. The instance is responsible for recreating its container
     * when it handles the event. Returns null if unsupported for this event.
     */
    protected T resurrectInstance(String key, WorkflowEvent event) {
        return null;
    }

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

    /**
     * Drop the live instance and close its run. The run row and its history stay
     * — that is the point of the store — only the container attachment goes.
     */
    public T removeInstance(String key) {
        var removed = instances.remove(key);
        if (removed != null && removed.runId() != null) {
            try {
                runs.closeRun(removed.runId(), RunStatus.COMPLETED);
            } catch (RuntimeException e) {
                log.warn("Failed to close run {} for {}", removed.runId(), key, e);
            }
        }
        return removed;
    }

    public boolean hasInstance(String key) {
        return instances.containsKey(key);
    }

    public Map<String, T> allInstances() {
        return Collections.unmodifiableMap(instances);
    }
}
