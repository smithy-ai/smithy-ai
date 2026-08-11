package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.runtime.store.RunStore;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Tell another run something happened.
 *
 * <p>This removes the hop that shaped the old event model: a child had no way to
 * reach its parent, so it went out through the VCS and came back in as a
 * webhook, and the parent recognised it by string-matching the bot's own comment
 * for "Development plan". Two event types existed purely to carry that
 * round trip. A signal goes straight to the target run instead.
 *
 * <p>Delivery is durable rather than immediate: the signal lands in the target's
 * history and releases any wait it was holding on that key. Whether the target
 * then runs a transition is the engine's decision, which keeps a signal safe to
 * emit at any point in a step list.
 */
@Slf4j
@Component
public class SignalEmitAction implements WorkflowAction {

    private final RunStore store;

    public SignalEmitAction(RunStore store) {
        this.store = store;
    }

    @Override
    public String type() {
        return "signal.emit";
    }

    @Override
    public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
        Object signal = input.get("signal");
        if (signal == null || String.valueOf(signal).isBlank()) {
            throw new IllegalArgumentException("signal.emit requires 'signal'");
        }
        String name = String.valueOf(signal);
        String target = resolveTarget(context, input);

        var payload = new LinkedHashMap<String, Object>(input);
        payload.remove("signal");
        payload.remove("to");
        payload.put("from", context.run().id());

        store.appendEvent(target, "signal:" + name, payload);
        int released = store.satisfyWait(target, name);

        log.info("Run {} signalled '{}' to {} (released {} wait(s))", context.run().id(), name, target, released);
        return Map.of("signal", name, "to", target, "released", released);
    }

    /** {@code to: parent} or an explicit run id; the parent is the common case. */
    private String resolveTarget(ActionContext context, Map<String, Object> input) {
        Object to = input.get("to");
        String target =
            to == null || "parent".equals(String.valueOf(to)) ? context.run().parentRunId() : String.valueOf(to);
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException(
                "signal.emit has no target: run %s has no parent and no 'to' was given".formatted(context.run().id())
            );
        }
        if (store.find(target).isEmpty()) {
            throw new IllegalArgumentException("signal.emit target run " + target + " does not exist");
        }
        return target;
    }
}
