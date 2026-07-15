package dev.smithyai.orchestrator.service.docker;

import dev.smithyai.orchestrator.service.docker.dto.ContainerConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerState;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ContainerSession {

    private final ContainerService service;

    @Getter
    private final String containerName;

    private ContainerState cachedState;

    public ContainerSession(String containerName, ContainerService service) {
        this.containerName = containerName;
        this.service = service;
    }

    // ── Container init ──────────────────────────────────────

    public void initContainer(ContainerConfig config, String initialStage) {
        cachedState = ContainerState.init(config.workflowType(), initialStage);
        service.create(containerName, config);
        service.writeState(containerName, cachedState);
    }

    // ── Container state ──────────────────────────────────────

    public ContainerState getState() {
        if (cachedState == null) {
            cachedState = service.readState(containerName);
        }
        return cachedState;
    }

    public void updateState(UnaryOperator<ContainerState> mutator) {
        cachedState = mutator.apply(getState());
        service.writeState(containerName, cachedState);
    }

    public ContainerState readState() {
        cachedState = service.readState(containerName);
        return cachedState;
    }

    public void writeState(ContainerState state) {
        cachedState = state;
        service.writeState(containerName, state);
    }

    public boolean exists() {
        return service.containerExists(containerName);
    }

    // ── Container operations ───────────────────────────────

    public ExecResult exec(String... command) {
        return exec(List.of(command));
    }

    public ExecResult exec(List<String> command) {
        return exec(command, null);
    }

    public ExecResult exec(List<String> command, Map<String, String> env) {
        return service.exec(containerName, command, env, null, null);
    }

    public ExecResult exec(List<String> command, Duration timeout, String stdinInput) {
        return service.exec(containerName, command, null, timeout, stdinInput);
    }

    public void copyToContainer(String destDir, byte[] data, String filename) {
        service.copyToContainer(containerName, destDir, data, filename);
    }

    public void destroy() {
        service.destroy(containerName);
    }
}
