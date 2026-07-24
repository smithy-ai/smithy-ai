package dev.smithyai.orchestrator.service.docker;

import dev.smithyai.orchestrator.service.docker.dto.ContainerConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerState;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Abstraction over the task-container backend. Implementations drive the lifecycle of long-lived
 * task containers the orchestrator execs into repeatedly. {@link DockerContainerRuntime} shells out
 * to the {@code docker} CLI; {@link dev.smithyai.orchestrator.service.docker.KubernetesContainerRuntime}
 * launches each task as a Kubernetes Pod.
 *
 * <p>Workflow code depends only on this interface (and on {@link ContainerSession}), so the same
 * orchestrator image runs unchanged on Docker Compose or Kubernetes based on {@code runtime.type}.
 */
public interface ContainerRuntime {
    // ── Sessions ─────────────────────────────────────────────

    /** Create a per-task session handle bound to this runtime. */
    ContainerSession createSession(String name);

    // ── Lifecycle ────────────────────────────────────────────

    /** Create and start the task container/Pod and block until its init completes. */
    void create(String name, ContainerConfig init);

    /** Stop and remove the task container/Pod. */
    void destroy(String name);

    /** True if a container/Pod with this name exists. */
    boolean containerExists(String name);

    // ── Discovery ────────────────────────────────────────────

    /** Names of running managed task containers/Pods (label {@code smithy.managed=true}). */
    List<String> listManagedContainers();

    /** True if a managed container/Pod (running or not) with this name exists. */
    boolean isManagedContainer(String name);

    // ── Exec ─────────────────────────────────────────────────

    /**
     * Run a command inside the task container/Pod in the {@code /workspace} working directory.
     *
     * @param environment optional extra environment for the command
     * @param timeout     optional timeout; runtime default applied when null
     * @param stdinInput  optional stdin piped to the process
     */
    ExecResult exec(String name, List<String> command, Map<String, String> environment, Duration timeout, String stdinInput);

    // ── Logs ─────────────────────────────────────────────────

    String fetchLogs(String name, int tailLines);

    String fetchOwnLogs(int tailLines);

    String fetchSessionTranscript(String name, String sessionId);

    // ── File transfer ────────────────────────────────────────

    byte[] copyFromContainer(String name, String path);

    void copyToContainer(String name, String destDir, byte[] data, String filename);

    // ── State ────────────────────────────────────────────────

    ContainerState readState(String name);

    void writeState(String name, ContainerState state);

    Optional<ContainerState> readStateSafe(String name);
}
