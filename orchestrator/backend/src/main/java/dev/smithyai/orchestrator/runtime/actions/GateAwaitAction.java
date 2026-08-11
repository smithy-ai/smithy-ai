package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.runtime.store.RunStore;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Hold a workflow at a checkpoint until something releases it.
 *
 * <p>Non-blocking, like {@code run.await}: what a gate waits for — a human
 * approving a plan, a sibling reaching a reviewable state — arrives as a webhook
 * minutes or days later, so the gate records itself in the run store and the
 * transition ends. When the release arrives the workflow is dispatched again and
 * this step reports satisfied.
 *
 * <p>The gate does not care who releases it. A dashboard approval, an approval
 * label on the issue and a child's {@code signal.emit} all satisfy the same key,
 * which is what lets a definition switch between them without changing shape.
 */
@Slf4j
@Component
public class GateAwaitAction implements WorkflowAction {

    private final RunStore store;

    public GateAwaitAction(RunStore store) {
        this.store = store;
    }

    @Override
    public String type() {
        return "gate.await";
    }

    @Override
    public boolean idempotent() {
        // Arming an already-armed gate is a no-op, and a satisfied gate stays
        // satisfied — so a replayed transition walks straight past it.
        return true;
    }

    @Override
    public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
        Object key = input.get("key");
        if (key == null || String.valueOf(key).isBlank()) {
            throw new IllegalArgumentException("gate.await requires 'key'");
        }
        String kind = input.get("kind") != null ? String.valueOf(input.get("kind")) : "gate";

        boolean satisfied = store.openWait(context.run().id(), kind, String.valueOf(key));
        if (!satisfied) {
            log.info("Run {} is waiting at gate '{}'", context.run().id(), key);
        }
        return Map.of("satisfied", satisfied, "key", String.valueOf(key));
    }
}
