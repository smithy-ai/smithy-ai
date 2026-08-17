package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.config.ConnectorRegistry;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinition;
import dev.smithyai.orchestrator.runtime.engine.RunEngine;
import dev.smithyai.orchestrator.runtime.engine.WorkflowRegistry;
import dev.smithyai.orchestrator.runtime.store.RunStatus;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
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
    private final WorkflowRegistry workflows;
    private final ConnectorRegistry connectors;

    public RunSpawnAction(RunStore store, @org.springframework.context.annotation.Lazy WorkflowRegistry workflows) {
        this(store, workflows, null);
    }

    @Autowired
    public RunSpawnAction(
        RunStore store,
        @org.springframework.context.annotation.Lazy WorkflowRegistry workflows,
        @Nullable ConnectorRegistry connectors
    ) {
        this.store = store;
        this.workflows = workflows;
        this.connectors = connectors;
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

        var definition = workflows == null ? java.util.Optional.<WorkflowDefinition>empty() : workflows.find(workflow);
        if (workflows != null && definition.isEmpty()) {
            // Spawning a workflow this orchestrator cannot run would leave a run
            // nothing will ever advance, and a parent waiting on a child that
            // never starts.
            throw new IllegalArgumentException(
                "run.spawn names workflow '%s', which is not loaded here".formatted(workflow)
            );
        }
        var child = store.create(
            workflow,
            definition.map(d -> d.metadata().version()).orElse(null),
            initialState,
            parentRunId
        );
        store.updateStatus(child.id(), RunStatus.PENDING);

        // The child's own workflow variables first — its branch prefix, its plan
        // directory, its tool lists. A run started by an event gets these from
        // the engine, and a spawned one that did not would fail on its first
        // step for want of a constant it never asked about.
        var vars = new LinkedHashMap<String, Object>(definition.map(WorkflowDefinition::vars).orElseGet(Map::of));
        // Then what the parent handed it, which wins.
        vars.putAll(input);
        vars.remove("workflow");
        vars.remove("state");
        // Which provider is behind a connector is configuration, so nothing
        // that names one — least of all an agent writing a plan — has to
        // restate it correctly for the child to know what system it is in.
        Object source = vars.get(RunEngine.SOURCE_VAR);
        if (connectors != null && source != null && !String.valueOf(source).isBlank()) {
            vars.put(RunEngine.SOURCE_PROVIDER_VAR, connectors.provider(String.valueOf(source)));
        }
        if (!vars.isEmpty()) {
            store.mergeVars(child.id(), vars);
        }

        log.info("Run {} spawned child {} ({})", parentRunId, child.id(), workflow);
        return Map.of("runId", child.id(), "workflow", workflow);
    }
}
