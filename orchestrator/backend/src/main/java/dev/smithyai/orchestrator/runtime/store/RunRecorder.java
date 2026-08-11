package dev.smithyai.orchestrator.runtime.store;

import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Records the hardcoded flows into the run store.
 *
 * <p>The flows still key their live instances on the container name; this keeps
 * a durable run alongside each one so history survives the container and the
 * dashboard has something to list. When the workflow engine replaces the
 * factories, runs stop being a mirror and become the primary record.
 */
@Slf4j
@Component
public class RunRecorder {

    /** The environment kind for a task container. */
    public static final String CONTAINER = "container";

    private final RunStore store;

    public RunRecorder(RunStore store) {
        this.store = store;
    }

    /**
     * Return the run already attached to this container, or start one. Recovery
     * and resurrection both land here, so a restarted orchestrator re-attaches
     * to the existing run rather than forking a second one for the same work.
     */
    public String openRun(String workflowName, String containerName, String initialState, WorkflowEvent event) {
        var existing = store.findByEnvironment(CONTAINER, containerName);
        if (existing.isPresent()) {
            return existing.get().id();
        }

        var run = store.create(workflowName, null, initialState, null);
        store.attachEnvironment(run.id(), CONTAINER, containerName, null);
        store.correlate(CorrelationKind.CONTAINER, containerName, run.id());
        correlateEvent(run.id(), event);
        store.updateStatus(run.id(), RunStatus.RUNNING);
        log.debug("Opened run {} for {} ({})", run.id(), containerName, workflowName);
        return run.id();
    }

    /** Index the run by whatever handle the triggering event carries. */
    public void correlateEvent(String runId, WorkflowEvent event) {
        if (event == null) return;
        switch (event) {
            case WorkflowEvent.IssueScoped e -> store.correlate(
                CorrelationKind.ISSUE,
                issueRef(e.ctx().info().owner(), e.ctx().info().repo(), e.ctx().issueRef()),
                runId
            );
            case WorkflowEvent.PrScoped e -> {
                store.correlate(
                    CorrelationKind.PR,
                    prRef(e.prc().info().owner(), e.prc().info().repo(), e.prc().number()),
                    runId
                );
                if (e.prc().headBranch() != null && !e.prc().headBranch().isBlank()) {
                    store.correlate(
                        CorrelationKind.BRANCH,
                        branchRef(e.prc().info().owner(), e.prc().info().repo(), e.prc().headBranch()),
                        runId
                    );
                }
            }
            default -> {
                // Push and CI events carry no handle of their own; they are
                // routed by branch, which a PR-scoped event already recorded.
            }
        }
    }

    public void recordState(String runId, String state) {
        if (runId == null) return;
        store.updateState(runId, state);
    }

    public void recordEvent(String runId, String type, Map<String, Object> payload) {
        if (runId == null) return;
        try {
            store.appendEvent(runId, type, payload);
        } catch (RuntimeException e) {
            // History must never break a workflow.
            log.warn("Failed to append run event {} for {}", type, runId, e);
        }
    }

    /** The run is done; its container is going away but the record stays. */
    public void closeRun(String runId, RunStatus status) {
        if (runId == null) return;
        store.updateStatus(runId, status);
        store.findEnvironmentNames(runId, CONTAINER).forEach(name -> store.detachEnvironment(CONTAINER, name));
    }

    public Optional<Run> findByContainer(String containerName) {
        return store.findByEnvironment(CONTAINER, containerName);
    }

    /**
     * The container a run holds, if any. Routing uses this to answer "which
     * session owns this PR?" from a correlation rather than by parsing a branch
     * name — which is what let flow-specific naming leak into the adapters.
     */
    public Optional<String> containerFor(Run run) {
        return store.findEnvironmentNames(run.id(), CONTAINER).stream().findFirst();
    }

    public Optional<String> containerForPr(String owner, String repo, int number) {
        return store.findByCorrelation(CorrelationKind.PR, prRef(owner, repo, number)).flatMap(this::containerFor);
    }

    public Optional<String> containerForBranch(String owner, String repo, String branch) {
        return store
            .findByCorrelation(CorrelationKind.BRANCH, branchRef(owner, repo, branch))
            .flatMap(this::containerFor);
    }

    // ── Correlation reference formats ────────────────────────

    public static String issueRef(String owner, String repo, String issueRef) {
        return "%s/%s#%s".formatted(owner, repo, issueRef);
    }

    public static String prRef(String owner, String repo, int number) {
        return "%s/%s!%d".formatted(owner, repo, number);
    }

    public static String branchRef(String owner, String repo, String branch) {
        return "%s/%s@%s".formatted(owner, repo, branch);
    }
}
