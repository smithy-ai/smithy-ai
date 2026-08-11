package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.runtime.store.RunStatus;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Start a child run of another workflow.
 *
 * <p>The parent/child relationship lives in the run store, never in the issue
 * tracker: not every tracker has sub-issues, and a parent story may live in Jira
 * while the work lives in VCS repositories. A coordinator therefore creates
 * ordinary issues in each target repository and records the relationship here.
 */
@Slf4j
@Component
public class RunSpawnAction implements WorkflowAction {

    private final RunStore store;

    public RunSpawnAction(RunStore store) {
        this.store = store;
    }

    @Override
    public String type() {
        return "run.spawn";
    }

    @Override
    public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
        String workflow = required(input, "workflow");
        String initialState = input.get("state") != null ? String.valueOf(input.get("state")) : "new";
        String parentRunId = context.run().id();

        var child = store.create(workflow, null, initialState, parentRunId);
        store.updateStatus(child.id(), RunStatus.PENDING);

        // Anything else under `with:` becomes the child's starting variables, so
        // a coordinator can hand each child its own task without a side channel.
        var vars = new LinkedHashMap<String, Object>(input);
        vars.remove("workflow");
        vars.remove("state");
        if (!vars.isEmpty()) {
            store.mergeVars(child.id(), vars);
        }

        log.info("Run {} spawned child {} ({})", parentRunId, child.id(), workflow);
        return Map.of("runId", child.id(), "workflow", workflow);
    }
}
