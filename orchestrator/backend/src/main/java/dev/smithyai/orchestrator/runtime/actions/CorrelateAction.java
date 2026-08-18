package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.runtime.store.CorrelationKind;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Index this run by an external handle, so a later event about that handle
 * routes back here.
 *
 * <p>This is what replaces encoding parentage in issue text. The foreman
 * appended a {@code "Parent story: <ref>"} marker to every child issue body and
 * regex-parsed it back out; a correlation is the same statement made in the
 * platform rather than in prose, and it works for trackers with no notion of
 * sub-issues.
 */
@Component
public class CorrelateAction implements WorkflowAction {

    private final RunStore store;

    public CorrelateAction(RunStore store) {
        this.store = store;
    }

    @Override
    public String type() {
        return "correlate";
    }

    @Override
    public boolean idempotent() {
        // Pointing the same handle at the same run again changes nothing.
        return true;
    }

    @Override
    public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
        var kind = CorrelationKind.fromValue(String.valueOf(input.get("kind")));
        String ref = String.valueOf(input.get("ref"));
        if (ref.isBlank() || "null".equals(ref)) {
            throw new IllegalArgumentException("correlate requires a non-empty 'ref'");
        }
        String runId = input.get("run") != null ? String.valueOf(input.get("run")) : context.run().id();

        store.correlate(kind, ref, runId);
        return Map.of("kind", kind.value(), "ref", ref, "run", runId);
    }
}
