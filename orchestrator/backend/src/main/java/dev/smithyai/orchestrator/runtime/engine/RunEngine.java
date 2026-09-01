package dev.smithyai.orchestrator.runtime.engine;

import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.definition.RepositoryWorkflowLoader;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinition;
import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.runtime.store.CorrelationKind;
import dev.smithyai.orchestrator.runtime.store.Run;
import dev.smithyai.orchestrator.runtime.store.RunRecorder;
import dev.smithyai.orchestrator.runtime.store.RunStatus;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** What the engine signals a parent when one of its children reaches a terminal state. */
    public static final String CHILD_DONE = "child-done";

    private final WorkflowRegistry registry;
    private final WorkflowRouter router;
    private final StepExecutor executor;
    private final RunStore store;
    private final RunEnvironments environments;
    private final RepositoryWorkflowLoader repositoryWorkflows;
    private final EventDebouncer debouncer;
    private final RunLocks locks;

    public RunEngine(
        WorkflowRegistry registry,
        WorkflowRouter router,
        StepExecutor executor,
        RunStore store,
        RunEnvironments environments,
        RepositoryWorkflowLoader repositoryWorkflows,
        EventDebouncer debouncer,
        RunLocks locks
    ) {
        this.registry = registry;
        this.router = router;
        this.executor = executor;
        this.store = store;
        this.environments = environments;
        this.repositoryWorkflows = repositoryWorkflows;
        this.debouncer = debouncer;
        this.locks = locks;
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
                      .map(registry::resolved)
                      .toList();
        if (repositoryOwned.isEmpty()) return registry.all();
        // A repository's own definition wins over the one it shares a name with,
        // which is what "highest precedence" in the docs means and the opposite
        // of what appending them did.
        var owned = repositoryOwned
            .stream()
            .map(d -> d.metadata().name())
            .collect(java.util.stream.Collectors.toSet());
        var all = new java.util.ArrayList<>(repositoryOwned);
        registry
            .all()
            .stream()
            .filter(d -> !owned.contains(d.metadata().name()))
            .forEach(all::add);
        return all;
    }

    private Outcome apply(WorkflowRouter.Decision decision, WorkflowEvent event) {
        var definition = definitionFor(decision, event);
        String key = scopedKey(decision);
        String name = definition.metadata().name();

        // Whoever already owns the thing this event is about — a run this
        // workflow started, or one another workflow spawned and correlated.
        var owner = ownerOf(event);
        var existing = (
            decision.by() != null
                ? byCorrelation(decision.by(), event)
                : store.findByCorrelation(CorrelationKind.KEY, key)
        ).or(() -> owner.filter(run -> run.workflowName().equals(name)));

        return switch (decision.action()) {
            // Resolution and creation under a per-key lock: two identical
            // deliveries arriving together must not each conclude "no run yet"
            // and race two runs — and two same-named containers — into
            // existence. Only the bookkeeping is locked; the dispatch below
            // runs outside it, serialized per run by the run's own lock.
            case create -> {
                Run target = locks.inRun("create|" + (key == null ? name : key), () -> {
                    var fresh = key == null
                        ? Optional.<Run>empty()
                        : store.findByCorrelation(CorrelationKind.KEY, key);
                    var own = ownerOf(event);
                    return resolveForCreate(
                        definition,
                        key,
                        fresh.or(() -> own.filter(run -> run.workflowName().equals(name))),
                        own,
                        event
                    );
                });
                yield target == null ? Outcome.ignored(name) : dispatch(definition, target, event);
            }
            case dispatch -> existing
                .map(run -> dispatch(definition, run, event))
                .orElseGet(() -> {
                    // Normal, not an error: an event about work this workflow
                    // never started — a human's pull request, someone else's issue.
                    log.debug("{}: no run for key {}", decision.workflowName(), key);
                    return Outcome.ignored(decision.workflowName());
                });
            case destroy -> existing
                // Cancelled, not completed: a human taking the agent off a task
                // has not delivered it, and anything waiting on it must keep
                // waiting.
                .map(run -> close(run, RunStatus.CANCELLED))
                .orElseGet(() -> Outcome.ignored(decision.workflowName()));
            case ignore -> Outcome.ignored(decision.workflowName());
        };
    }

    /**
     * The run this event should be dispatched to: the one that already owns
     * the key, a fresh one, or none at all (stand aside).
     *
     * <p>The adoption case is what makes a coordinator work. It creates a child
     * issue, spawns the run that will do the work, and correlates the two; the
     * assignment webhook then arrives and must find that run rather than open a
     * second, unrelated one beside it.
     *
     * <p>The stand-aside case is what stops a coordinator fanning out from its
     * own children. It listens for assigned issues, and every child it creates
     * is an assigned issue — so without this a configured coordinator would
     * plan a feature for each task of the feature it just planned.
     */
    private Run resolveForCreate(
        WorkflowDefinition definition,
        String key,
        Optional<Run> existing,
        Optional<Run> owner,
        WorkflowEvent event
    ) {
        if (existing.isEmpty() && owner.isPresent()) {
            Run owned = owner.get();
            if (
                event instanceof WorkflowEvent.IssueAssigned &&
                owned.parentRunId() == null &&
                !owned.workflowName().equals(definition.metadata().name())
            ) {
                if (!owned.isTerminal()) close(owned, RunStatus.CANCELLED);
                log.info(
                    "Transferring issue from run {} ({}) to workflow {}",
                    owned.id(),
                    owned.workflowName(),
                    definition.metadata().name()
                );
                return start(definition, key, event);
            }
            log.debug(
                "{} stands aside: this work already belongs to run {} ({})",
                definition.metadata().name(),
                owner.get().id(),
                owner.get().workflowName()
            );
            return null;
        }
        return existing
            .map(found -> found.isTerminal() ? reopen(definition, found) : found)
            .orElseGet(() -> start(definition, key, event));
    }

    /** The run that already owns the thing this event is about, whatever workflow it belongs to. */
    private Optional<Run> ownerOf(WorkflowEvent event) {
        for (String by : List.of("issue", "pr", "branch")) {
            var run = byCorrelation(by, event);
            if (run.isPresent()) return run;
        }
        return Optional.empty();
    }

    /**
     * Find the run through something it registered earlier.
     *
     * <p>A pull-request or CI event carries no issue reference. The old flows
     * recovered one by parsing it out of the branch name, which is how a flow's
     * naming convention ended up inside the provider adapters. The run recorded
     * what it opened; this reads that back.
     */
    private Optional<Run> byCorrelation(String by, WorkflowEvent event) {
        return switch (by) {
            case "pr" -> event instanceof WorkflowEvent.PrScoped pr
                ? store.findByCorrelation(
                      CorrelationKind.PR,
                      RunRecorder.prRef(pr.prc().info().owner(), pr.prc().info().repo(), pr.prc().number())
                  )
                : Optional.empty();
            case "issue" -> event instanceof WorkflowEvent.IssueScoped issue
                ? store.findByCorrelation(
                      CorrelationKind.ISSUE,
                      RunRecorder.issueRef(
                          issue.ctx().info().owner(),
                          issue.ctx().info().repo(),
                          issue.ctx().issueRef()
                      )
                  )
                : Optional.empty();
            case "branch" -> branchOf(event).flatMap(branch ->
                store.findByCorrelation(
                    CorrelationKind.BRANCH,
                    RunRecorder.branchRef(event.info().owner(), event.info().repo(), branch)
                )
            );
            case "container" -> Optional.empty();
            default -> Optional.empty();
        };
    }

    /**
     * The branch a push or CI event is about.
     *
     * <p>Deliberately not PR-scoped events: those name their pull request, and
     * that is the only handle that may find a run for them. Matching them by
     * branch made a run adopt someone else's merge request from the same
     * branch — a human's MR opened from the agent's work branch got the agent
     * answering its comments and pushing to it, with no assignment anywhere.
     */
    private static Optional<String> branchOf(WorkflowEvent event) {
        return switch (event) {
            case WorkflowEvent.HumanPush push -> Optional.ofNullable(push.branch());
            case WorkflowEvent.CiFailure ci -> Optional.ofNullable(ci.ciRun().headBranch());
            case WorkflowEvent.CiRecovery ci -> Optional.ofNullable(ci.ciRun().headBranch());
            default -> Optional.empty();
        };
    }

    /**
     * A routing key is only unique within its workflow: two workflows may both
     * track the same story, and a shared correlation row would make the second
     * silently take over the first's run.
     */
    private static String scopedKey(WorkflowRouter.Decision decision) {
        return decision.key() == null ? null : decision.workflowName() + "|" + decision.key();
    }

    /** A decision names a workflow; it may be one this repository brought with it. */
    private WorkflowDefinition definitionFor(WorkflowRouter.Decision decision, WorkflowEvent event) {
        // Same list, same order the routing decision came from — reaching for
        // the global registry first would run a different definition than the
        // one that claimed the event.
        return candidates(event)
            .stream()
            .filter(candidate -> candidate.metadata().name().equals(decision.workflowName()))
            .findFirst()
            .orElseThrow(() ->
                new IllegalStateException("No workflow definition named '" + decision.workflowName() + "'")
            );
    }

    private Run start(WorkflowDefinition definition, String key, WorkflowEvent event) {
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
        // Which connector this run came in through, so anything the engine
        // emits about it later — a signal to a parent, a gate released from the
        // dashboard — still says which system the work lives in. A definition
        // that names `source` itself means something by it and keeps it.
        if (event != null && !event.source().isBlank()) {
            var sourceVars = new LinkedHashMap<String, Object>();
            if (!definition.vars().containsKey(SOURCE_VAR)) sourceVars.put(SOURCE_VAR, event.source());
            if (!definition.vars().containsKey(SOURCE_PROVIDER_VAR)) {
                sourceVars.put(SOURCE_PROVIDER_VAR, event.sourceInfo().provider());
            }
            if (!sourceVars.isEmpty()) store.mergeVars(run.id(), sourceVars);
        }
        store.correlate(CorrelationKind.KEY, key, run.id());
        if (event instanceof WorkflowEvent.IssueScoped issue) {
            store.correlate(
                CorrelationKind.ISSUE,
                RunRecorder.issueRef(issue.ctx().info().owner(), issue.ctx().info().repo(), issue.ctx().issueRef()),
                run.id()
            );
        }
        store.updateStatus(run.id(), RunStatus.RUNNING);
        log.info("Started run {} of {} for {}", run.id(), definition.metadata().name(), key);
        return store.find(run.id()).orElseThrow();
    }

    /**
     * Put a stopped run back to work.
     *
     * <p>Cancelling is how a person takes the agent off a task, and assigning it
     * again is how they hand it back. The run keeps its id, its variables and
     * its history, so a coordinator still counting it as a child sees it become
     * pending again rather than losing track of it.
     *
     * <p>It starts from the beginning: the container it had was destroyed when
     * it stopped, and a half-finished transition has nothing left to resume
     * into. A run that <em>completed</em> is left alone; that work was
     * delivered, and a stray assignment event is not a reason to do it twice.
     */
    private Run reopen(WorkflowDefinition definition, Run stopped) {
        if (stopped.status() == RunStatus.COMPLETED) {
            log.debug("Run {} already completed; not restarting it", stopped.id());
            return stopped;
        }
        String initial = definition.state().getInitial();
        store.clearSteps(stopped.id());
        store.clearPendingWaits(stopped.id());
        store.updateState(stopped.id(), initial);
        store.updateStatus(stopped.id(), RunStatus.RUNNING);
        store.appendEvent(stopped.id(), "run.reopened", Map.of("was", stopped.status().value(), "state", initial));
        log.info("Run {} ({}) reopened from {}", stopped.id(), stopped.workflowName(), stopped.status().value());
        return store.find(stopped.id()).orElse(stopped);
    }

    /**
     * One event at a time per run.
     *
     * <p>Two webhooks about the same run arriving together would otherwise run
     * its steps concurrently — two agent turns in one container, two pull
     * requests from one branch. The schema always intended leases to serialize
     * this; a monitor is enough while one orchestrator owns the store, and it
     * does not leave a lock behind if the process dies.
     */
    private Outcome dispatch(WorkflowDefinition definition, Run run, WorkflowEvent event) {
        return locks.inRun(run.id(), () -> {
            // Re-read inside the lock. The run was resolved before it, so another
            // event may have moved it since — and acting on the state it used to
            // be in runs the wrong transition.
            var current = store.find(run.id()).orElse(run);
            return dispatchSerially(definition, current, event);
        });
    }

    private Outcome dispatchSerially(WorkflowDefinition definition, Run run, WorkflowEvent event) {
        if (store.isLeased(run.id())) {
            // Someone is driving this session by hand. Acting on a webhook on
            // top of what they are typing produces work neither asked for.
            log.info("Run {} is under human control, holding {}", run.id(), event.name());
            store.appendEvent(run.id(), "event.held", Map.of("event", event.name()));
            return Outcome.ignored(definition.metadata().name());
        }
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

        String transitionId = StepExecutor.transitionId(run.state(), event);
        store.updateStatus(run.id(), RunStatus.RUNNING);
        store.appendEvent(run.id(), event.name(), null);

        try {
            executor.execute(run, event, transitionId, transition.steps(), Map.of(), definition.actions());
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
        notifyParent(run);
        return new Outcome(run.workflowName(), run.id(), run.state(), run.state(), true);
    }

    /**
     * Tell the parent its child is done.
     *
     * <p>Emitted by the engine rather than by each workflow, because a child
     * has no reason to know it is one — any workflow can be spawned by a
     * coordinator, and requiring every one of them to remember a signal.emit on
     * every terminal path is how a coordinator ends up waiting forever.
     */
    private void notifyParent(Run run) {
        if (run.parentRunId() == null) return;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("child", run.id());
        payload.put("workflow", run.workflowName());
        payload.put(
            "status",
            store
                .find(run.id())
                .map(r -> r.status().value())
                .orElse("completed")
        );
        run.vars().forEach((name, value) -> payload.putIfAbsent(name, value));
        try {
            // Recorded as what happened, not as the signal: the engine also
            // records the signal when the parent handles it, and a timeline
            // showing the same name twice for one child reads like a duplicate.
            store.appendEvent(run.parentRunId(), "child.finished", payload);
            store.satisfyWait(run.parentRunId(), CHILD_DONE);
            deliver(
                run.parentRunId(),
                new WorkflowEvent.Signal(run.vars().isEmpty() ? null : repoOf(run), CHILD_DONE, payload)
            );
        } catch (RuntimeException e) {
            // The child's work is done and pushed; a coordinator that cannot
            // react must not undo that.
            log.error("Run {} could not be told its child {} finished", run.parentRunId(), run.id(), e);
        }
    }

    /** The repository a child was working in, so a signal reads like any other event. */
    static dev.smithyai.orchestrator.model.RepoInfo repoOf(Run run) {
        Object owner = run.vars().get("owner");
        Object repo = run.vars().get("repo");
        if (owner == null || repo == null) return null;
        return new dev.smithyai.orchestrator.model.RepoInfo(
            String.valueOf(owner),
            String.valueOf(repo),
            null,
            String.valueOf(run.vars().getOrDefault(SOURCE_VAR, "")),
            String.valueOf(run.vars().getOrDefault(SOURCE_PROVIDER_VAR, ""))
        );
    }

    /** The run variable holding the connector a run arrived through. */
    public static final String SOURCE_VAR = "source";

    /** The provider implementation behind {@link #SOURCE_VAR}. */
    public static final String SOURCE_PROVIDER_VAR = "sourceProvider";

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
        var definition = candidates(signal)
            .stream()
            .filter(candidate -> candidate.metadata().name().equals(run.get().workflowName()))
            .findFirst();
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
