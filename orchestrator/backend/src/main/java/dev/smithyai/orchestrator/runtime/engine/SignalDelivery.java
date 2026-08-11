package dev.smithyai.orchestrator.runtime.engine;

import dev.smithyai.orchestrator.model.events.WorkflowEvent;

/**
 * Hands a signal to the run it is addressed to.
 *
 * <p>Exists so {@code signal.emit} can wake the target run without the action
 * package depending on the engine that executes it — the engine holds the
 * executor, which holds the registry, which holds the actions.
 */
public interface SignalDelivery {
    /**
     * Dispatch a signal to one run, bypassing routing because the target is
     * already known.
     *
     * @return whether the target's current state handled it
     */
    boolean deliver(String targetRunId, WorkflowEvent.Signal signal);
}
