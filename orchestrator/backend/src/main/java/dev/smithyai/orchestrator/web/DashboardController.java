package dev.smithyai.orchestrator.web;

import dev.smithyai.orchestrator.service.docker.ContainerService;
import dev.smithyai.orchestrator.web.dto.InstanceDto;
import dev.smithyai.orchestrator.workflow.shared.AbstractWorkflowFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DashboardController {

    private final List<AbstractWorkflowFactory<?>> factories;
    private final ContainerService containerService;

    public DashboardController(List<AbstractWorkflowFactory<?>> factories, ContainerService containerService) {
        this.factories = factories;
        this.containerService = containerService;
    }

    @GetMapping("/dashboard/instances")
    public List<InstanceDto> listInstances() {
        var runningContainers = new HashSet<>(containerService.listManagedContainers());
        var result = new ArrayList<InstanceDto>();

        for (var factory : factories) {
            for (var entry : factory.allInstances().entrySet()) {
                var instance = entry.getValue();
                var state = instance.session().getState();
                result.add(new InstanceDto(
                    instance.containerName(),
                    state.workflowType().value(),
                    state.stage(),
                    state.lastProcessedAt(),
                    state.ciPaused(),
                    state.ciRetryCount(),
                    runningContainers.contains(instance.containerName())
                ));
            }
        }
        return result;
    }

    @GetMapping("/auth/check")
    public ResponseEntity<Void> authCheck() {
        return ResponseEntity.ok().build();
    }
}
