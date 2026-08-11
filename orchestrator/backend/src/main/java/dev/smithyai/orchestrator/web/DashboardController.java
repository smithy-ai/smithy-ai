package dev.smithyai.orchestrator.web;

import dev.smithyai.orchestrator.runtime.engine.RunTakeover;
import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.runtime.store.RunEvent;
import dev.smithyai.orchestrator.runtime.store.RunRecorder;
import dev.smithyai.orchestrator.runtime.store.RunStatus;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import dev.smithyai.orchestrator.runtime.store.RunWait;
import dev.smithyai.orchestrator.service.docker.ContainerService;
import dev.smithyai.orchestrator.service.metrics.MetricsRecorder;
import dev.smithyai.orchestrator.web.dto.InstanceDto;
import dev.smithyai.orchestrator.web.dto.MessageRequest;
import dev.smithyai.orchestrator.web.dto.RunDto;
import dev.smithyai.orchestrator.web.dto.TakeoverDto;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final ContainerService containerService;
    private final MetricsRecorder metrics;
    private final RunStore runStore;
    private final RunEnvironments environments;
    private final RunTakeover takeover;

    public DashboardController(
        ContainerService containerService,
        MetricsRecorder metrics,
        RunStore runStore,
        RunEnvironments environments,
        RunTakeover takeover
    ) {
        this.containerService = containerService;
        this.metrics = metrics;
        this.runStore = runStore;
        this.environments = environments;
        this.takeover = takeover;
    }

    @GetMapping("/dashboard/metrics")
    public Map<String, Object> metricsSummary() {
        return metrics.summarize();
    }

    /**
     * Runs, newest first — including finished and failed ones. This is the view
     * that survives a container being removed.
     */
    @GetMapping("/dashboard/runs")
    public List<RunDto> listRuns(@RequestParam(defaultValue = "100") int limit) {
        var running = new HashSet<>(containerService.listManagedContainers());
        return runStore
            .findRecent(Math.clamp(limit, 1, 500))
            .stream()
            .map(run -> {
                var containers = runStore.findEnvironmentNames(run.id(), RunRecorder.CONTAINER);
                boolean live = containers.stream().anyMatch(running::contains);
                return RunDto.from(run, containers, live);
            })
            .toList();
    }

    @GetMapping("/dashboard/runs/{runId}")
    public ResponseEntity<RunDto> getRun(@PathVariable String runId) {
        return runStore
            .find(runId)
            .map(run -> {
                var containers = runStore.findEnvironmentNames(run.id(), RunRecorder.CONTAINER);
                var running = new HashSet<>(containerService.listManagedContainers());
                return ResponseEntity.ok(RunDto.from(run, containers, containers.stream().anyMatch(running::contains)));
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** A run's timeline, oldest first. */
    @GetMapping("/dashboard/runs/{runId}/events")
    public ResponseEntity<List<RunEvent>> getRunEvents(@PathVariable String runId) {
        if (runStore.find(runId).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(runStore.findEvents(runId));
    }

    /** Direct children of a run — the shape a fan-out workflow produces. */
    @GetMapping("/dashboard/runs/{runId}/children")
    public ResponseEntity<List<RunDto>> getRunChildren(@PathVariable String runId) {
        if (runStore.find(runId).isEmpty()) return ResponseEntity.notFound().build();
        var running = new HashSet<>(containerService.listManagedContainers());
        return ResponseEntity.ok(
            runStore
                .findChildren(runId)
                .stream()
                .map(child -> {
                    var containers = runStore.findEnvironmentNames(child.id(), RunRecorder.CONTAINER);
                    return RunDto.from(child, containers, containers.stream().anyMatch(running::contains));
                })
                .toList()
        );
    }

    /** What a run is blocked on — an approval nobody has given, a sibling that has not finished. */
    @GetMapping("/dashboard/runs/{runId}/waits")
    public ResponseEntity<List<RunWait>> getRunWaits(@PathVariable String runId) {
        if (runStore.find(runId).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(runStore.findPendingWaits(runId));
    }

    /**
     * Approve a gate the run is holding at.
     *
     * <p>The approval a workflow waits for is usually a label or a comment on
     * the issue, but that only works where the work is visible. A coordinator's
     * plan spans repositories, so the dashboard has to be able to release it
     * too — the gate does not care which of them does.
     */
    @PostMapping("/dashboard/runs/{runId}/waits/{key}")
    public ResponseEntity<Map<String, Object>> approveWait(@PathVariable String runId, @PathVariable String key) {
        if (runStore.find(runId).isEmpty()) return ResponseEntity.notFound().build();
        int released = runStore.satisfyWait(runId, key);
        runStore.appendEvent(runId, "gate.approved", Map.of("key", key, "via", "dashboard"));
        log.info("Gate '{}' on run {} approved from the dashboard", key, runId);
        return ResponseEntity.ok(Map.of("key", key, "released", released));
    }

    /**
     * Stop a run.
     *
     * <p>Cancelling is a decision about the run, not about the container: the
     * container goes, the history stays, and the dashboard keeps showing what
     * happened and where it stopped.
     */
    @DeleteMapping("/dashboard/runs/{runId}")
    public ResponseEntity<RunDto> cancelRun(@PathVariable String runId) {
        var run = runStore.find(runId);
        if (run.isEmpty()) return ResponseEntity.notFound().build();
        if (run.get().isTerminal()) return ResponseEntity.ok(RunDto.from(run.get(), List.of(), false));

        try {
            environments.destroyContainer(run.get());
        } catch (RuntimeException e) {
            log.warn("Could not release the container while cancelling run {}", runId, e);
        }
        runStore.appendEvent(runId, "run.cancelled", Map.of("via", "dashboard"));
        runStore.updateStatus(runId, RunStatus.CANCELLED);
        log.info("Run {} cancelled from the dashboard", runId);
        return ResponseEntity.ok(RunDto.from(runStore.find(runId).orElseThrow(), List.of(), false));
    }

    /**
     * Runs that currently hold a container.
     *
     * <p>Kept as a separate view from the run list because it answers a
     * different question: not "what has happened" but "what is running right
     * now, and can I get at it".
     */
    @GetMapping("/dashboard/instances")
    public List<InstanceDto> listInstances() {
        var running = new HashSet<>(containerService.listManagedContainers());
        var result = new ArrayList<InstanceDto>();
        for (var run : runStore.findActive()) {
            for (String container : runStore.findEnvironmentNames(run.id(), RunRecorder.CONTAINER)) {
                result.add(
                    new InstanceDto(
                        container,
                        run.workflowName(),
                        run.state(),
                        run.updatedAt(),
                        Boolean.TRUE.equals(run.vars().get("ciPaused")),
                        run.vars().get("ciAttempts") instanceof Number n ? n.intValue() : 0,
                        running.contains(container),
                        takeover.isHeld(run.id())
                    )
                );
            }
        }
        return result;
    }

    @GetMapping("/auth/check")
    public ResponseEntity<Void> authCheck() {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/dashboard/logs/orchestrator")
    public String orchestratorLogs(@RequestParam(defaultValue = "200") int tail) {
        return containerService.fetchOwnLogs(tail);
    }

    @GetMapping("/dashboard/logs/instance/{containerName}")
    public ResponseEntity<String> instanceLogs(
        @PathVariable String containerName,
        @RequestParam(defaultValue = "200") int tail
    ) {
        if (!containerService.isManagedContainer(containerName)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(containerService.fetchLogs(containerName, tail));
    }

    @GetMapping("/dashboard/session/{containerName}")
    public ResponseEntity<String> instanceSession(@PathVariable String containerName) {
        if (!containerService.isManagedContainer(containerName)) {
            return ResponseEntity.notFound().build();
        }
        var state = containerService.readStateSafe(containerName);
        if (state.isEmpty() || state.get().sessionId() == null) {
            return ResponseEntity.ok("");
        }
        return ResponseEntity.ok(containerService.fetchSessionTranscript(containerName, state.get().sessionId()));
    }

    // ── Human takeover ───────────────────────────────────────

    /**
     * Addressed by container name because that is what the session panel knows.
     * The lease itself belongs to the run, so it survives the container being
     * rebuilt underneath it.
     */
    private Optional<dev.smithyai.orchestrator.runtime.store.Run> runFor(String containerName) {
        return runStore.findByEnvironment(RunRecorder.CONTAINER, containerName);
    }

    @GetMapping("/dashboard/takeover/{containerName}")
    public ResponseEntity<TakeoverDto> takeoverStatus(@PathVariable String containerName) {
        var run = runFor(containerName);
        if (run.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(new TakeoverDto(takeover.isHeld(run.get().id()), null));
    }

    @PostMapping("/dashboard/takeover/{containerName}")
    public ResponseEntity<TakeoverDto> takeoverHeartbeat(@PathVariable String containerName) {
        var run = runFor(containerName);
        if (run.isEmpty()) return ResponseEntity.notFound().build();
        // Empty means someone else already has it; saying so beats two people
        // typing into the same agent session.
        return takeover
            .heartbeat(run.get().id())
            .map(expiresAt -> ResponseEntity.ok(new TakeoverDto(true, expiresAt)))
            .orElseGet(() -> ResponseEntity.status(409).build());
    }

    @DeleteMapping("/dashboard/takeover/{containerName}")
    public ResponseEntity<Void> releaseTakeover(@PathVariable String containerName) {
        var run = runFor(containerName);
        if (run.isEmpty()) return ResponseEntity.notFound().build();
        takeover.release(run.get().id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/dashboard/takeover/{containerName}/message")
    public ResponseEntity<String> sendTakeoverMessage(
        @PathVariable String containerName,
        @RequestBody MessageRequest request
    ) {
        var run = runFor(containerName);
        if (run.isEmpty()) return ResponseEntity.notFound().build();
        if (request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest().body("Message text is required");
        }
        if (!takeover.isHeld(run.get().id())) {
            return ResponseEntity.status(409).body("No active takeover for this run");
        }
        return ResponseEntity.ok(takeover.send(run.get(), request.text(), List.of()));
    }
}
