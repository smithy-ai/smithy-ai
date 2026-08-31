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
        this(containerName, service, null);
    }

    public ContainerSession(String containerName, ContainerService service, ContainerState seedState) {
        this.containerName = containerName;
        this.service = service;
        this.cachedState = seedState;
    }

    // ── Container init ──────────────────────────────────────

    public void initContainer(ContainerConfig config, String initialStage) {
        // Extra repos live outside the workspace, so every agent turn must name
        // them (--add-dir) or their files are unreadable. Recorded in the state
        // rather than re-derived, so turns after a restart still know them.
        var extraDirs = config.extraRepos().stream().map(ContainerConfig.ExtraRepo::path).toList();
        cachedState = ContainerState.init(config.workflow(), initialStage).withExtraDirs(extraDirs);
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

    /**
     * Make a directory under {@code .smithy/tmp/} ready to be written into.
     *
     * <p>Copying a file in is a bare {@code cat >}, so the directory has to
     * exist first. And everything under that path is material the run was
     * given rather than work it produced — an attachment, a rendered design —
     * so git is told to ignore it locally, where the exclusion travels with
     * neither the branch nor a commit.
     *
     * @return whether the directory is now usable
     */
    public boolean ensureScratchDir(String dir) {
        var result = exec(
            List.of(
                "sh",
                "-c",
                "mkdir -p '" +
                    dir.replace("'", "'\\''") +
                    "' && { grep -qxF '.smithy/tmp/' .git/info/exclude 2>/dev/null" +
                    " || echo '.smithy/tmp/' >> .git/info/exclude; }"
            )
        );
        if (result.exitCode() != 0) {
            log.warn("Failed to prepare {} in {}: {}", dir, containerName, result.stderr());
            return false;
        }
        return true;
    }

    public void destroy() {
        service.destroy(containerName);
    }
}
