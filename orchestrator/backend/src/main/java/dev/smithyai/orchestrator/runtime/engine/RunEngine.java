package dev.smithyai.orchestrator.runtime.engine;

import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.definition.RepositoryWorkflowLoader;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinition;
import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.runtime.store.CorrelationKind;
import dev.smithyai.orchestrator.runtime.store.Run;
import dev.smithyai.orchestrator.runtime.store.RunStatus;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs a workflow definition against a durable run.
 *
 * <p>This is what the definition package was missing: the previous attempt
 * shipped a parser and a validator whose beans nothing injected, so a
 * definition could be written and checked but never executed.
 *
 * <p>Handling an event is: find the definition's decision about it, resolve or
 * create the run it belongs to, look up the transition for the run's current
 * state, execute its steps, then move the run. Nothing here knows what a plan,
 * a pull request or a review round is — those are all in the YAML.
 */
@Slf4j
@Component
public class RunEngine implements SignalDelivery {

    private final WorkflowRegistry registry;
    private final WorkflowRouter router;
    private final StepExecutor executor;
    private final RunStore store;
    private final RunEnvironments environments;
    private final RepositoryWorkflowLoader repositoryWorkflows;
    private final EventDebouncer debouncer;

    public RunEngine(
        WorkflowRegistry registry,
        WorkflowRouter router,
        StepExecutor executor,
        RunStore store,
        RunEnvironments environments,
        RepositoryWorkflowLoader repositoryWorkflows,
        EventDebouncer debouncer
    ) {
        this.registry = registry;
        this.router = router;
        this.executor = executor;
        this.store = store;
        this.environments = environments;
        this.repositoryWorkflows = repositoryWorkflows;
        this.debouncer = debouncer;
    }

    /** What an event did, for logs and for the tests that assert on routing. */
    public record Outcome(String workflowName, String runId, String fromState, String toState, boolean handled) {
        static Outcome ignored(String workflowName) {
            return new Outcome(workflowName, null, null, null, false);
        }
    }

    /**
     * Offer an event to every definition. More than one may act on it — a
     * coordinator and the child working the same repository both care about the
     * same pull request — so this returns every outcome rather than the first.
     */
    public java.util.List<Outcome> handle(WorkflowEvent event) {
        var outcomes = new java.util.ArrayList<Outcome>();
        for (var decision : router.route(event, candidates(event))) {
            try {
                outcomes.add(apply(decision, event));
            } catch (RuntimeException e) {
                log.error("Workflow {} failed on {}", decision.workflowName(), event.name(), e);
                outcomes.add(Outcome.ignored(decision.workflowName()));
            }
        }
        if (outcomes.isEmpty()) {
            log.debug("No workflow claimed {}", event.name());
        }
        return outcomes;
    }

    /**
     * The definitions that get a say about this event: the ones this
     * orchestrator has loaded, plus any the event's own repository carries.
     */
    private java.util.List<WorkflowDefinition> candidates(WorkflowEvent event) {
        var repositoryOwned =
            repositoryWorkflows == null
                ? java.util.List.<WorkflowDefinition>of()
                : repositoryWorkflows
                      .forRepository(event.info())
                      .stream()
                      .map(dev.smithyai.orchestrator.runtime.definition.LoadedWorkflowDefinition::definition)
                      .filter(definition -> registry.runnable(definition))
                      .toList();
        if (repositoryOwned.isEmpty()) return registry.all();
        var all = new java.util.ArrayList<>(registry.all());
        all.addAll(repositoryOwned);
        return all;
    }

    private Outcome apply(WorkflowRouter.Decision decision, WorkflowEvent event) {
        var definition = definitionFor(decision, event);
        String key = scopedKey(decision);
        var existing = store.findByCorrelation(CorrelationKind.KEY, key);

        return switch (decision.action()) {
            case create -> dispatch(definition, existing.orElseGet(() -> create(definition, key)), event);
            case dispatch -> existing
                .map(run -> dispatch(definition, run, event))
                .orElseGet(() -> {
                    // Normal, not an error: an event about work this workflow
                    // never started — a human's pull request, someone else's issue.
                    log.debug("{}: no run for key {}", decision.workflowName(), key);
                    return Outcome.ignored(decision.workflowName());
                });
            case destroy -> existing
                .map(run -> close(run, RunStatus.COMPLETED))
                .orElseGet(() -> Outcome.ignored(decision.workflowName()));
            case ignore -> Outcome.ignored(decision.workflowName());
        };
    }

    /**
     * A routing key is only unique within its workflow: two workflows may both
     * track the same story, and a shared correlation row would make the second
     * silently take over the first's run.
     */
    private static String scopedKey(WorkflowRouter.Decision decision) {
        return decision.workflowName() + "|" + decision.key();
    }

    /** A decision names a workflow; it may be one this repository brought with it. */
    private WorkflowDefinition definitionFor(WorkflowRouter.Decision decision, WorkflowEvent event) {
        return registry
            .find(decision.workflowName())
            .orElseGet(() ->
                candidates(event)
                    .stream()
                    .filter(candidate -> candidate.metadata().name().equals(decision.workflowName()))
                    .findFirst()
                    .orElseThrow(() ->
                        new IllegalStateException("No workflow definition named '" + decision.workflowName() + "'")
                    )
            );
    }

    private Run create(WorkflowDefinition definition, String key) {
        var run = store.create(
            definition.metadata().name(),
            definition.metadata().version(),
            definition.state().getInitial(),
            null
        );
        // Workflow-level vars seed the run, so a definition's constants — review
        // lenses, attempt caps, branch patterns — are readable as `vars.x`
        // without every step repeating them.
        if (!definition.vars().isEmpty()) {
            store.updateVars(run.id(), definition.vars());
        }
        store.correlate(CorrelationKind.KEY, key, run.id());
        store.updateStatus(run.id(), RunStatus.RUNNING);
        log.info("Started run {} of {} for {}", run.id(), definition.metadata().name(), key);
        return store.find(run.id()).orElseThrow();
    }

    private Outcome dispatch(WorkflowDefinition definition, Run run, WorkflowEvent event) {
        if (run.isTerminal()) {
            log.debug("Run {} is {}, ignoring {}", run.id(), run.status().value(), event.name());
            return Outcome.ignored(definition.metadata().name());
        }

        noteVersionChange(definition, run);

        var stage = definition.state().getStages().get(run.state());
        if (stage == null) {
            // The definition changed under a run that was mid-flight, and the
            // change was not benign. Surfaced rather than guessed at, because
            // guessing here silently strands work — and recorded once, so the
            // history says what happened without every later event repeating it.
            log.warn("Run {} is in state '{}', which {} no longer defines", run.id(), run.state(), run.workflowName());
            recordOnce(run.id(), "state.undefined", Map.of("state", run.state()));
            store.updateStatus(run.id(), RunStatus.WAITING);
            return Outcome.ignored(definition.metadata().name());
        }

        var transition = stage.on().get(event.name());
        if (transition == null) {
            log.debug("{}: state '{}' does not handle {}", run.workflowName(), run.state(), event.name());
            return Outcome.ignored(definition.metadata().name());
        }

        var window = transition.debounceWindow();
        if (window != null && !(event instanceof WorkflowEvent.Batch)) {
            // The burst has not finished arriving yet. Nothing is lost: the
            // transition runs once the window closes, over everything collected.
            String batchKey = run.id() + ":" + event.name();
            debouncer.submit(batchKey, window, event, events ->
                dispatch(
                    definition,
                    store.find(run.id()).orElse(run),
                    new WorkflowEvent.Batch(events.getLast(), events)
                )
            );
            store.updateStatus(run.id(), RunStatus.WAITING);
            return new Outcome(definition.metadata().name(), run.id(), run.state(), run.state(), false);
        }

        String transitionId = StepExecutor.transitionId(run.state(), event.name());
        store.updateStatus(run.id(), RunStatus.RUNNING);
        store.appendEvent(run.id(), event.name(), null);

        try {
            executor.execute(run, event, transitionId, transition.steps());
        } catch (RuntimeException e) {
            // A failed transition leaves the run where it was, so the fix is to
            // resend the event rather than to reconstruct state by hand.
            store.updateStatus(run.id(), RunStatus.WAITING);
            store.appendEvent(run.id(), "transition.failed", failure(transitionId, e));
            throw e;
        }

        String from = run.state();
        String to = resolveNextState(transition.to(), run);
        if (to != null && !to.equals(from)) {
            store.updateState(run.id(), to);
        }
        if (to != null && to.equals(definition.state().getTerminal())) {
            close(store.find(run.id()).orElseThrow(), RunStatus.COMPLETED);
        } else {
            store.updateStatus(run.id(), RunStatus.WAITING);
        }
        return new Outcome(definition.metadata().name(), run.id(), from, to == null ? from : to, true);
    }

    /**
     * A run started under one version of a definition and is being handled by
     * another. Not fatal on its own — most edits are additive — but it is the
     * first thing to look at when a run behaves oddly, so it goes in the
     * history rather than only in a log line.
     */
    private void noteVersionChange(WorkflowDefinition definition, Run run) {
        String current = definition.metadata().version();
        if (current == null || current.equals(run.workflowVersion())) return;
        recordOnce(run.id(), "workflow.version_changed", versionChange(run.workflowVersion(), current));
    }

    private static Map<String, Object> versionChange(String from, String to) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("from", String.valueOf(from));
        payload.put("to", to);
        return payload;
    }

    /** Append only if this run has not already said it. */
    private void recordOnce(String runId, String type, Map<String, Object> payload) {
        boolean already = store
            .findEvents(runId)
            .stream()
            .anyMatch(event -> type.equals(event.type()));
        if (!already) store.appendEvent(runId, type, payload);
    }

    /**
     * Where the run goes next.
     *
     * <p>A step may have moved it already — a review that decides between
     * sending work back and letting it through does that with {@code state.set}
     * — and an explicit decision by a step outranks the transition's declared
     * destination.
     */
    private String resolveNextState(String declared, Run run) {
        return store
            .find(run.id())
            .map(Run::state)
            .filter(current -> !current.equals(run.state()))
            .orElse(declared);
    }

    private Outcome close(Run run, RunStatus status) {
        environments.destroyContainer(run);
        store.updateStatus(run.id(), status);
        log.info("Run {} ({}) finished: {}", run.id(), run.workflowName(), status.value());
        return new Outcome(run.workflowName(), run.id(), run.state(), run.state(), true);
    }

    private static Map<String, Object> failure(String transitionId, RuntimeException e) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("transition", transitionId);
        payload.put("error", String.valueOf(e.getMessage()));
        return payload;
    }

    /**
     * Deliver a signal straight to one run.
     *
     * <p>The transition runs on the sender's thread. That is deliberate: a
     * child announcing it is ready and its coordinator reacting are one causal
     * step, and queueing the second half would let the coordinator observe a
     * child state that has already moved on.
     */
    @Override
    public boolean deliver(String targetRunId, WorkflowEvent.Signal signal) {
        var run = store.find(targetRunId);
        if (run.isEmpty()) {
            log.warn("Signal {} addressed to unknown run {}", signal.name(), targetRunId);
            return false;
        }
        var definition = registry.find(run.get().workflowName());
        if (definition.isEmpty()) {
            // The target runs a hardcoded flow, or a definition that is no
            // longer loaded. The signal is still recorded in its history.
            log.debug("Run {} has no loaded definition; signal {} recorded only", targetRunId, signal.name());
            return false;
        }
        return dispatch(definition.get(), run.get(), signal).handled();
    }

    /** The run a workflow's routing key points at, for callers that only want to look. */
    public Optional<Run> findByKey(String workflowName, String key) {
        return store.findByCorrelation(CorrelationKind.KEY, workflowName + "|" + key);
    }
}
