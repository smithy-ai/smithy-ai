package dev.smithyai.orchestrator.web;

import dev.smithyai.orchestrator.runtime.store.RunEvent;
import dev.smithyai.orchestrator.runtime.store.RunRecorder;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import dev.smithyai.orchestrator.service.docker.ContainerService;
import dev.smithyai.orchestrator.service.metrics.MetricsRecorder;
import dev.smithyai.orchestrator.web.dto.InstanceDto;
import dev.smithyai.orchestrator.web.dto.MessageRequest;
import dev.smithyai.orchestrator.web.dto.RunDto;
import dev.smithyai.orchestrator.web.dto.TakeoverDto;
import dev.smithyai.orchestrator.workflow.shared.AbstractWorkflowFactory;
import dev.smithyai.orchestrator.workflow.shared.AbstractWorkflowInstance;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    private final List<AbstractWorkflowFactory<?>> factories;
    private final ContainerService containerService;
    private final MetricsRecorder metrics;
    private final RunStore runStore;

    public DashboardController(
        List<AbstractWorkflowFactory<?>> factories,
        ContainerService containerService,
        MetricsRecorder metrics,
        RunStore runStore
    ) {
        this.factories = factories;
        this.containerService = containerService;
        this.metrics = metrics;
        this.runStore = runStore;
    }

    @GetMapping("/dashboard/metrics")
    public java.util.Map<String, Object> metricsSummary() {
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

    @GetMapping("/dashboard/instances")
    public List<InstanceDto> listInstances() {
        var runningContainers = new HashSet<>(containerService.listManagedContainers());
        var result = new ArrayList<InstanceDto>();

        for (var factory : factories) {
            for (var entry : factory.allInstances().entrySet()) {
                var instance = entry.getValue();
                boolean running = runningContainers.contains(instance.containerName());
                try {
                    var state = instance.session().getState();
                    result.add(
                        new InstanceDto(
                            instance.containerName(),
                            state.workflowType().value(),
                            state.stage(),
                            state.lastProcessedAt(),
                            state.ciPaused(),
                            state.ciRetryCount(),
                            running,
                            instance.isHumanControlled()
                        )
                    );
                } catch (Exception e) {
                    log.warn("Could not read state for {}: {}", instance.containerName(), e.getMessage());
                    result.add(
                        new InstanceDto(
                            instance.containerName(),
                            null,
                            null,
                            null,
                            false,
                            0,
                            running,
                            instance.isHumanControlled()
                        )
                    );
                }
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

    @GetMapping("/dashboard/takeover/{containerName}")
    public ResponseEntity<TakeoverDto> takeoverStatus(@PathVariable String containerName) {
        var instance = findInstance(containerName);
        if (instance == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new TakeoverDto(instance.isHumanControlled(), null));
    }

    @PostMapping("/dashboard/takeover/{containerName}")
    public ResponseEntity<TakeoverDto> takeoverHeartbeat(@PathVariable String containerName) {
        var instance = findInstance(containerName);
        if (instance == null) {
            return ResponseEntity.notFound().build();
        }
        Instant expiresAt = instance.takeoverHeartbeat();
        return ResponseEntity.ok(new TakeoverDto(true, expiresAt));
    }

    @DeleteMapping("/dashboard/takeover/{containerName}")
    public ResponseEntity<Void> releaseTakeover(@PathVariable String containerName) {
        var instance = findInstance(containerName);
        if (instance == null) {
            return ResponseEntity.notFound().build();
        }
        instance.releaseTakeover();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/dashboard/takeover/{containerName}/message")
    public ResponseEntity<String> sendTakeoverMessage(
        @PathVariable String containerName,
        @RequestBody MessageRequest request
    ) {
        var instance = findInstance(containerName);
        if (instance == null) {
            return ResponseEntity.notFound().build();
        }
        if (request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest().body("Message text is required");
        }
        if (!instance.isHumanControlled()) {
            return ResponseEntity.status(409).body("No active takeover for this instance");
        }
        // Keep the lease alive while Claude processes the message
        instance.takeoverHeartbeat();
        String reply = instance.sendHumanMessage(request.text());
        return ResponseEntity.ok(reply);
    }

    private AbstractWorkflowInstance findInstance(String containerName) {
        for (var factory : factories) {
            AbstractWorkflowInstance instance = factory.getInstance(containerName);
            if (instance != null) {
                return instance;
            }
        }
        return null;
    }
}
