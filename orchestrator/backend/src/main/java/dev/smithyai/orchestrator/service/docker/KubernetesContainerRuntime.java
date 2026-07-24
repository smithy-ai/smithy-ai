package dev.smithyai.orchestrator.service.docker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.ClaudeConfig;
import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.RuntimeConfig.KubernetesRuntimeConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerState;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.PodResource;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link ContainerRuntime} that runs each task as a Kubernetes Pod, driving it through the
 * Kubernetes API instead of the {@code docker} CLI. Task Pods are long-lived (running
 * {@code smithy-init}, {@code restartPolicy: Never}) and exec'd into repeatedly, mirroring the
 * Docker runtime's model; state lives in {@code /tmp/smithy-state.json} inside the Pod.
 *
 * <p>Wired only when {@code runtime.type=kubernetes} (see
 * {@link dev.smithyai.orchestrator.config.RuntimeConfiguration}). The client authenticates via the
 * mounted ServiceAccount when running in-cluster.
 */
@Slf4j
public class KubernetesContainerRuntime implements ContainerRuntime, AutoCloseable {

    private static final String STATE_PATH = "/tmp/smithy-state.json";
    private static final String MANAGED_LABEL = "smithy.managed";
    private static final String WORKFLOW_LABEL = "smithy.workflow";
    private static final String TASK_CONTAINER = "task";
    private static final String WORKDIR = "/workspace";
    private static final Duration DEFAULT_EXEC_TIMEOUT = Duration.ofSeconds(30);

    private static final int INIT_TIMEOUT_SECONDS = 300;
    private static final int INIT_POLL_INTERVAL_MS = 1000;
    private static final int POD_START_TIMEOUT_SECONDS = 120;

    static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final KubernetesClient client;
    private final String namespace;
    private final KubernetesRuntimeConfig k8s;
    private final String taskImage;
    private final String vcsUrl;
    private final String vcsToken;
    private final String claudeOauthToken;
    private final String claudeApiKey;
    private final String gitAuthUser;
    private final String defaultGitEmail;

    public KubernetesContainerRuntime(
        KubernetesRuntimeConfig k8sConfig,
        DockerConfig dockerConfig,
        ClaudeConfig claudeConfig,
        VcsProviderConfig vcsConfig,
        BotConfig botConfig
    ) {
        this(new KubernetesClientBuilder().build(), k8sConfig, dockerConfig, claudeConfig, vcsConfig, botConfig);
    }

    // Package-visible for tests (Fabric8 mock server injects a client).
    KubernetesContainerRuntime(
        KubernetesClient client,
        KubernetesRuntimeConfig k8sConfig,
        DockerConfig dockerConfig,
        ClaudeConfig claudeConfig,
        VcsProviderConfig vcsConfig,
        BotConfig botConfig
    ) {
        this.client = client;
        this.k8s = k8sConfig;
        this.namespace = k8sConfig.resolvedNamespace();
        this.taskImage = dockerConfig.taskImage();
        this.vcsUrl = vcsConfig.resolvedUrl();
        this.vcsToken = vcsConfig.smithyToken();
        this.claudeOauthToken = claudeConfig.oauthToken();
        this.claudeApiKey = claudeConfig.apiKey();
        this.gitAuthUser = vcsConfig.gitAuthUser();
        this.defaultGitEmail = botConfig.resolvedSmithyEmail();
        log.info("Kubernetes runtime active (namespace={}, taskImage={})", namespace, taskImage);
    }

    // ── Public API ───────────────────────────────────────────

    @Override
    public ContainerSession createSession(String name) {
        return new ContainerSession(name, this);
    }

    @Override
    public boolean containerExists(String name) {
        return pod(name).get() != null;
    }

    @Override
    public List<String> listManagedContainers() {
        return client
            .pods()
            .inNamespace(namespace)
            .withLabel(MANAGED_LABEL, "true")
            .list()
            .getItems()
            .stream()
            .filter(p -> "Running".equals(phase(p)))
            .map(p -> p.getMetadata().getName())
            .toList();
    }

    @Override
    public boolean isManagedContainer(String name) {
        Pod p = pod(name).get();
        return p != null && p.getMetadata().getLabels() != null && "true".equals(p.getMetadata().getLabels().get(MANAGED_LABEL));
    }

    @Override
    public Optional<ContainerState> readStateSafe(String name) {
        try {
            byte[] data = copyFromContainer(name, STATE_PATH);
            return Optional.of(MAPPER.readValue(data, ContainerState.class));
        } catch (Exception e) {
            log.warn("Failed to read state from {}: {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Pod lifecycle ────────────────────────────────────────

    @Override
    public void create(String name, ContainerConfig init) {
        Pod pod = buildPod(name, init);
        try {
            client.pods().inNamespace(namespace).resource(pod).create();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create task Pod " + name + ": " + e.getMessage(), e);
        }
        log.info("Created Pod {}, waiting for it to start...", name);
        waitForRunning(name);
        log.info("Pod {} running, waiting for init...", name);
        waitForInit(name);
    }

    Pod buildPod(String name, ContainerConfig init) {
        List<EnvVar> env = buildEnv(init);
        List<Volume> volumes = new ArrayList<>();
        List<VolumeMount> mounts = new ArrayList<>();
        if (init.cacheVolumes() != null) {
            init
                .cacheVolumes()
                .forEach((vol, path) -> {
                    String volName = sanitizeVolumeName(vol);
                    volumes.add(new VolumeBuilder().withName(volName).withNewEmptyDir().endEmptyDir().build());
                    mounts.add(new VolumeMountBuilder().withName(volName).withMountPath(path).build());
                });
        }

        var labels = new LinkedHashMap<String, String>();
        labels.put(MANAGED_LABEL, "true");
        if (init.workflowType() != null) {
            labels.put(WORKFLOW_LABEL, init.workflowType().value());
        }

        var container = new PodBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(namespace)
            .withLabels(labels)
            .endMetadata()
            .withNewSpec()
            .withRestartPolicy("Never")
            .withAutomountServiceAccountToken(false)
            .withVolumes(volumes)
            .addNewContainer()
            .withName(TASK_CONTAINER)
            .withImage(taskImage)
            .withCommand("smithy-init")
            .withEnv(env)
            .withVolumeMounts(mounts)
            .withNewResources()
            .withRequests(resourceMap(k8s.taskCpuRequest(), k8s.taskMemoryRequest()))
            .withLimits(resourceMap(k8s.taskCpuLimit(), k8s.taskMemoryLimit()))
            .endResources()
            .endContainer()
            .endSpec();

        if (k8s.hasServiceAccount()) {
            container.editSpec().withServiceAccountName(k8s.taskServiceAccount()).endSpec();
        }
        if (k8s.hasImagePullSecret()) {
            container.editSpec().withImagePullSecrets(new LocalObjectReference(k8s.imagePullSecret())).endSpec();
        }
        return container.build();
    }

    private List<EnvVar> buildEnv(ContainerConfig init) {
        var env = new ArrayList<EnvVar>();
        if (claudeOauthToken != null && !claudeOauthToken.isBlank()) {
            env.add(envVar("CLAUDE_CODE_OAUTH_TOKEN", claudeOauthToken));
        }
        if (claudeApiKey != null && !claudeApiKey.isBlank()) {
            env.add(envVar("ANTHROPIC_API_KEY", claudeApiKey));
        }
        env.add(envVar("VCS_URL", vcsUrl));
        env.add(envVar("VCS_TOKEN", init.vcsToken() != null ? init.vcsToken() : vcsToken));
        env.add(envVar("CLONE_URL", init.cloneUrl()));
        env.add(envVar("BRANCH", init.branch()));
        env.add(envVar("SOURCE_BRANCH", init.sourceBranch() != null ? init.sourceBranch() : ""));
        env.add(envVar("GIT_EMAIL", init.gitEmail() != null ? init.gitEmail() : defaultGitEmail));
        env.add(envVar("GIT_USERNAME", init.gitUsername() != null ? init.gitUsername() : "Agent Smithy"));
        env.add(envVar("GIT_AUTH_USER", gitAuthUser));

        String extraReposJson = "";
        if (init.extraRepos() != null && !init.extraRepos().isEmpty()) {
            try {
                var repoLists = init
                    .extraRepos()
                    .stream()
                    .map(r -> List.of(r.cloneUrl(), r.path(), r.branch()))
                    .toList();
                extraReposJson = MAPPER.writeValueAsString(repoLists);
            } catch (Exception e) {
                log.warn("Failed to serialize extra repos", e);
            }
        }
        env.add(envVar("EXTRA_REPOS", extraReposJson));
        return env;
    }

    private void waitForRunning(String name) {
        long deadline = System.currentTimeMillis() + POD_START_TIMEOUT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Pod p = pod(name).get();
            if (p == null) {
                sleep();
                continue;
            }
            String phase = phase(p);
            if ("Running".equals(phase)) {
                return;
            }
            if ("Failed".equals(phase) || "Succeeded".equals(phase)) {
                throw new RuntimeException(
                    "Pod " + name + " terminated during startup (phase=" + phase + "). Logs:\n" + fetchLogsSafe(name, 50)
                );
            }
            String pullError = imagePullError(p);
            if (pullError != null) {
                throw new RuntimeException("Pod " + name + " cannot start: " + pullError);
            }
            sleep();
        }
        throw new RuntimeException("Pod " + name + " did not reach Running within " + POD_START_TIMEOUT_SECONDS + "s");
    }

    private void waitForInit(String name) {
        long deadline = System.currentTimeMillis() + INIT_TIMEOUT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (markerExists(name, "/tmp/smithy-init-done")) {
                log.info("Pod {} init completed successfully", name);
                return;
            }
            if (markerExists(name, "/tmp/smithy-init-failed")) {
                throw new RuntimeException("Pod " + name + " init failed. Logs:\n" + fetchLogsSafe(name, 50));
            }
            Pod p = pod(name).get();
            String phase = p == null ? null : phase(p);
            if (p == null || "Failed".equals(phase) || "Succeeded".equals(phase)) {
                throw new RuntimeException(
                    "Pod " + name + " stopped during init (phase=" + phase + "). Logs:\n" + fetchLogsSafe(name, 50)
                );
            }
            sleep();
        }
        log.warn("Pod {} init did not complete within {}s — proceeding anyway", name, INIT_TIMEOUT_SECONDS);
    }

    private boolean markerExists(String name, String path) {
        try {
            // Runs as: test -f <path>; exit 0 when present.
            ExecResult r = exec(name, List.of("test", "-f", path), null, Duration.ofSeconds(15), null);
            return r.exitCode() == 0;
        } catch (Exception e) {
            // Pod may not be exec-ready yet; treat as "not there yet".
            return false;
        }
    }

    @Override
    public void destroy(String name) {
        try {
            pod(name).withGracePeriod(0).delete();
            log.info("Destroyed Pod {}", name);
        } catch (Exception e) {
            log.warn("Failed to destroy Pod {}: {}", name, e.getMessage());
        }
    }

    // ── Exec ─────────────────────────────────────────────────

    @Override
    public ExecResult exec(String name, List<String> command, Map<String, String> environment, Duration timeout, String stdinInput) {
        byte[] stdin = stdinInput != null ? stdinInput.getBytes(StandardCharsets.UTF_8) : null;
        Capture c = execCapture(name, command, environment, timeout, stdin);
        return new ExecResult(c.exitCode, new String(c.stdout, StandardCharsets.UTF_8), c.stderr);
    }

    /**
     * Executes a command in the task container in {@code /workspace} with optional env and stdin,
     * capturing raw stdout bytes. The command list is passed verbatim as argv (no shell parsing of
     * its tokens): {@code sh -c 'cd /workspace && exec env "$@"' _ K=V ... cmd arg...}.
     */
    private Capture execCapture(String name, List<String> command, Map<String, String> environment, Duration timeout, byte[] stdin) {
        var argv = new ArrayList<String>();
        argv.add("sh");
        argv.add("-c");
        argv.add("cd " + WORKDIR + " && exec env \"$@\"");
        argv.add("smithy-exec"); // $0 placeholder
        if (environment != null) {
            environment.forEach((k, v) -> argv.add(k + "=" + v));
        }
        argv.addAll(command);

        Duration effective = timeout != null ? timeout : DEFAULT_EXEC_TIMEOUT;
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var pod = pod(name).inContainer(TASK_CONTAINER);

        ExecWatch watch = null;
        try {
            if (stdin != null) {
                watch = pod.redirectingInput().writingOutput(out).writingError(err).exec(argv.toArray(String[]::new));
                try (OutputStream os = watch.getInput()) {
                    os.write(stdin);
                }
            } else {
                watch = pod.writingOutput(out).writingError(err).exec(argv.toArray(String[]::new));
            }
            Integer code = watch.exitCode().get(effective.toMillis(), TimeUnit.MILLISECONDS);
            return new Capture(code != null ? code : -1, out.toByteArray(), err.toString(StandardCharsets.UTF_8));
        } catch (TimeoutException e) {
            return new Capture(124, out.toByteArray(), "Timed out after " + effective);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Capture(130, out.toByteArray(), "Interrupted");
        } catch (Exception e) {
            return new Capture(1, out.toByteArray(), "exec failed: " + e.getMessage());
        } finally {
            if (watch != null) {
                watch.close();
            }
        }
    }

    private record Capture(int exitCode, byte[] stdout, String stderr) {}

    // ── Logs ─────────────────────────────────────────────────

    @Override
    public String fetchLogs(String name, int tailLines) {
        try {
            return pod(name).tailingLines(tailLines).getLog();
        } catch (Exception e) {
            return "Failed to fetch logs for " + name + ": " + e.getMessage();
        }
    }

    private String fetchLogsSafe(String name, int tailLines) {
        return fetchLogs(name, tailLines);
    }

    @Override
    public String fetchOwnLogs(int tailLines) {
        String selfId = System.getenv("HOSTNAME");
        if (selfId == null || selfId.isBlank()) {
            return "Unable to determine own pod name (HOSTNAME not set)";
        }
        return fetchLogs(selfId, tailLines);
    }

    @Override
    public String fetchSessionTranscript(String name, String sessionId) {
        ExecResult r = exec(
            name,
            List.of(
                "sh",
                "-c",
                "cat \"$(find /root/.claude/projects -name '" + sessionId + ".jsonl' 2>/dev/null | head -1)\" 2>/dev/null"
            ),
            null,
            Duration.ofSeconds(30),
            null
        );
        return r.stdout();
    }

    // ── File transfer ────────────────────────────────────────

    @Override
    public byte[] copyFromContainer(String name, String path) {
        Capture c = execCapture(name, List.of("cat", path), null, Duration.ofSeconds(30), null);
        if (c.exitCode != 0) {
            throw new RuntimeException("Failed to read " + name + ":" + path + ": " + c.stderr);
        }
        return c.stdout;
    }

    @Override
    public void copyToContainer(String name, String destDir, byte[] data, String filename) {
        String safePath = destDir + "/" + filename;
        ExecResult result = exec(
            name,
            List.of("sh", "-c", "cat > '" + safePath.replace("'", "'\\''") + "'"),
            null,
            Duration.ofSeconds(30),
            new String(data, StandardCharsets.UTF_8)
        );
        if (result.exitCode() != 0) {
            throw new RuntimeException("Failed to copy to " + name + ":" + safePath + ": " + result.stderr());
        }
    }

    // ── State ────────────────────────────────────────────────

    @Override
    public ContainerState readState(String name) {
        try {
            byte[] data = copyFromContainer(name, STATE_PATH);
            return MAPPER.readValue(data, ContainerState.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read state from: " + name, e);
        }
    }

    @Override
    public void writeState(String name, ContainerState state) {
        try {
            byte[] data = MAPPER.writeValueAsBytes(state);
            copyToContainer(name, "/tmp", data, "smithy-state.json");
        } catch (Exception e) {
            throw new RuntimeException("Failed to write state to: " + name, e);
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    private PodResource pod(String name) {
        return client.pods().inNamespace(namespace).withName(name);
    }

    private static String phase(Pod p) {
        return p.getStatus() != null ? p.getStatus().getPhase() : null;
    }

    /** Returns a human-readable reason if the task container is stuck pulling its image, else null. */
    private static String imagePullError(Pod p) {
        if (p.getStatus() == null || p.getStatus().getContainerStatuses() == null) {
            return null;
        }
        for (ContainerStatus cs : p.getStatus().getContainerStatuses()) {
            if (cs.getState() != null && cs.getState().getWaiting() != null) {
                String reason = cs.getState().getWaiting().getReason();
                if ("ImagePullBackOff".equals(reason) || "ErrImagePull".equals(reason) || "InvalidImageName".equals(reason)) {
                    return reason + ": " + cs.getState().getWaiting().getMessage();
                }
            }
        }
        return null;
    }

    private static EnvVar envVar(String name, String value) {
        return new EnvVar(name, value == null ? "" : value, null);
    }

    private static Map<String, Quantity> resourceMap(String cpu, String memory) {
        var map = new LinkedHashMap<String, Quantity>();
        if (cpu != null && !cpu.isBlank()) {
            map.put("cpu", new Quantity(cpu.strip()));
        }
        if (memory != null && !memory.isBlank()) {
            map.put("memory", new Quantity(memory.strip()));
        }
        return map;
    }

    /** Kubernetes volume names must be DNS-1123 labels (lowercase alphanumeric and '-'). */
    private static String sanitizeVolumeName(String raw) {
        String v = raw.toLowerCase().replaceAll("[^a-z0-9-]", "-");
        v = v.replaceAll("(^-+)|(-+$)", "");
        return v.isBlank() ? "cache" : v;
    }

    private static void sleep() {
        try {
            Thread.sleep(INIT_POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while polling Pod state", e);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
