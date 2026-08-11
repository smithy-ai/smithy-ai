package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.runtime.store.RunStore;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Report whether this run's children have finished — the wave join.
 *
 * <p>Non-blocking by design: the orchestrator is event-driven, so a coordinator
 * evaluates this when a child reports in rather than parking a thread. The
 * outcome is exposed as {@code steps.<id>.satisfied} for a following step's
 * {@code if:} to gate on.
 */
@Component
public class RunAwaitAction implements WorkflowAction {

    private final RunStore store;

    public RunAwaitAction(RunStore store) {
        this.store = store;
    }

    @Override
    public String type() {
        return "run.await";
    }

    @Override
    public boolean idempotent() {
        // A pure read of child state.
        return true;
    }

    @Override
    public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
        var children = store.findChildren(context.run().id());
        long finished = children
            .stream()
            .filter(child -> child.status().isTerminal())
            .count();
        long failed = children
            .stream()
            .filter(child -> child.status() == dev.smithyai.orchestrator.runtime.store.RunStatus.FAILED)
            .count();

        // "all" by default; a number waits for that many, which is how a wave
        // releases before the whole fan-out is done.
        long required = children.size();
        Object countInput = input.get("count");
        if (countInput != null && !"all".equals(String.valueOf(countInput))) {
            required = Long.parseLong(String.valueOf(countInput));
        }

        boolean satisfied = !children.isEmpty() && finished >= required;
        return Map.of(
            "satisfied",
            satisfied,
            "total",
            children.size(),
            "finished",
            finished,
            "failed",
            failed,
            "pending",
            children.size() - finished
        );
    }

    /** Child run ids, for a step that wants to address them individually. */
    public List<String> childIds(ActionContext context) {
        return store
            .findChildren(context.run().id())
            .stream()
            .map(run -> run.id())
            .toList();
    }
}
