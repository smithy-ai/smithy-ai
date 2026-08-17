package dev.smithyai.orchestrator.service.docker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.ClaudeConfig;
import dev.smithyai.orchestrator.config.ConnectorRegistry;
import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerState;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ContainerService {

    private static final String STATE_PATH = "/tmp/smithy-state.json";
    static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final int INIT_TIMEOUT_SECONDS = 300;
    private static final int INIT_POLL_INTERVAL_MS = 1000;

    private final DockerCli docker;
    private final String network;
    private final String taskImage;
    private final String vcsUrl;
    private final String vcsToken;
    private final String claudeOauthToken;
    private final String claudeApiKey;
    private final String gitAuthUser;
    private final String defaultGitEmail;

    @Autowired
    public ContainerService(
        DockerConfig dockerConfig,
        ClaudeConfig claudeConfig,
        ConnectorRegistry connectors,
        DockerCli docker
    ) {
        String connector = connectors.defaultVcs();
        String actor = connectors.defaultActor();
        this.docker = docker;
        this.network = dockerConfig.network();
        this.taskImage = dockerConfig.taskImage();
        this.vcsUrl = connectors.connector(connector).url();
        this.vcsToken = connectors.token(connector, actor);
        this.claudeOauthToken = claudeConfig.oauthToken();
        this.claudeApiKey = claudeConfig.apiKey();
        this.gitAuthUser = connectors.gitAuthUser(connector);
        this.defaultGitEmail = connectors.gitEmail(connector, actor);
    }

    public ContainerService(
        DockerConfig dockerConfig,
        ClaudeConfig claudeConfig,
        VcsProviderConfig vcsConfig,
        BotConfig botConfig,
        DockerCli docker
    ) {
        this.docker = docker;
        this.network = dockerConfig.network();
        this.taskImage = dockerConfig.taskImage();
        this.vcsUrl = vcsConfig.resolvedUrl();
        this.vcsToken = vcsConfig.smithyToken();
        this.claudeOauthToken = claudeConfig.oauthToken();
        this.claudeApiKey = claudeConfig.apiKey();
        this.gitAuthUser = vcsConfig.gitAuthUser();
        this.defaultGitEmail = botConfig.resolvedSmithyEmail();
    }

    // ── Public API ───────────────────────────────────────────

    public ContainerSession createSession(String name) {
        return new ContainerSession(name, this);
    }

    /**
     * Create a session pre-seeded with state already read during recovery, so
     * callers of getState() don't need the container to be running.
     */
    public ContainerSession createSession(String name, ContainerState seedState) {
        return new ContainerSession(name, this, seedState);
    }

    public boolean containerExists(String containerName) {
        var result = docker.run(List.of("inspect", containerName));
        return result.exitCode() == 0;
    }

    public List<String> listManagedContainers() {
        var result = docker.run(List.of("ps", "--filter", "label=smithy.managed=true", "--format", "{{.Names}}"));
        if (result.exitCode() != 0) {
            log.warn("Failed to list managed containers: {}", result.stderr());
            return List.of();
        }
        return result
            .stdout()
            .lines()
            .map(String::strip)
            .filter(s -> !s.isBlank())
            .toList();
    }

    public List<String> listAllManagedContainers() {
        var result = docker.run(List.of("ps", "-a", "--filter", "label=smithy.managed=true", "--format", "{{.Names}}"));
        if (result.exitCode() != 0) {
            log.warn("Failed to list managed containers: {}", result.stderr());
            return List.of();
        }
        return result
            .stdout()
            .lines()
            .map(String::strip)
            .filter(s -> !s.isBlank())
            .toList();
    }

    public boolean ensureRunning(String containerName) {
        var inspectResult = docker.run(List.of("inspect", "--format", "{{.State.Running}}", containerName));
        if (inspectResult.exitCode() != 0) {
            log.warn("Failed to inspect container {}: {}", containerName, inspectResult.stderr());
            return false;
        }
        if ("true".equals(inspectResult.stdout().strip())) {
            return true;
        }
        log.info("Container {} is stopped, starting it", containerName);
        var startResult = docker.run(List.of("start", containerName));
        if (startResult.exitCode() != 0) {
            log.warn("Failed to start container {}: {}", containerName, startResult.stderr());
            return false;
        }
        return true;
    }

    public boolean isManagedContainer(String containerName) {
        var result = docker.run(List.of("ps", "-a", "--filter", "label=smithy.managed=true", "--format", "{{.Names}}"));
        if (result.exitCode() != 0) {
            log.warn("Failed to list managed containers: {}", result.stderr());
            return false;
        }
        return result.stdout().lines().map(String::strip).anyMatch(containerName::equals);
    }

    public Optional<ContainerState> readStateSafe(String containerName) {
        try {
            byte[] data = copyFromContainer(containerName, STATE_PATH);
            return Optional.of(MAPPER.readValue(data, ContainerState.class));
        } catch (Exception e) {
            log.warn("Failed to read state from {}: {}", containerName, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Container lifecycle ──────────────────────────────────

    void create(String name, ContainerConfig init) {
        var args = new ArrayList<String>();
        args.add("create");
        args.add("--name");
        args.add(name);
        args.add("--network");
        args.add(network);
        args.add("--restart");
        args.add("unless-stopped");

        // Labels
        args.add("--label");
        args.add("smithy.managed=true");
        if (init.workflow() != null) {
            args.add("--label");
            args.add("smithy.workflow=" + init.workflow());
        }

        // Volumes
        if (init.cacheVolumes() != null) {
            init
                .cacheVolumes()
                .forEach((vol, path) -> {
                    args.add("-v");
                    args.add(vol + ":" + path);
                });
        }

        // Environment
        if (claudeOauthToken != null && !claudeOauthToken.isBlank()) {
            args.add("-e");
            args.add("CLAUDE_CODE_OAUTH_TOKEN=" + claudeOauthToken);
        }
        if (claudeApiKey != null && !claudeApiKey.isBlank()) {
            args.add("-e");
            args.add("ANTHROPIC_API_KEY=" + claudeApiKey);
        }
        args.add("-e");
        args.add("VCS_URL=" + (init.vcsUrl() != null ? init.vcsUrl() : vcsUrl));
        args.add("-e");
        args.add("VCS_TOKEN=" + (init.vcsToken() != null ? init.vcsToken() : vcsToken));
        args.add("-e");
        args.add("CLONE_URL=" + init.cloneUrl());
        args.add("-e");
        args.add("BRANCH=" + init.branch());
        args.add("-e");
        args.add("SOURCE_BRANCH=" + (init.sourceBranch() != null ? init.sourceBranch() : ""));
        args.add("-e");
        args.add("GIT_EMAIL=" + (init.gitEmail() != null ? init.gitEmail() : defaultGitEmail));
        args.add("-e");
        args.add("GIT_USERNAME=" + (init.gitUsername() != null ? init.gitUsername() : "Agent Smithy"));

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
                // Continuing would build a container missing a repository the
                // workflow asked for, and the agent would then plan against
                // code it cannot see.
                throw new IllegalStateException("Cannot serialize extra repos for container " + name, e);
            }
        }
        args.add("-e");
        args.add("GIT_AUTH_USER=" + (init.gitAuthUser() != null ? init.gitAuthUser() : gitAuthUser));
        args.add("-e");
        args.add("EXTRA_REPOS=" + extraReposJson);

        // Image and command
        args.add(taskImage);
        args.add("smithy-init");

        var createResult = docker.run(args);
        if (createResult.exitCode() != 0) {
            throw new RuntimeException("Failed to create container " + name + ": " + createResult.stderr());
        }

        var startResult = docker.run(List.of("start", name));
        if (startResult.exitCode() != 0) {
            throw new RuntimeException("Failed to start container " + name + ": " + startResult.stderr());
        }

        log.info("Created container {}, waiting for init...", name);
        waitForInit(name);
    }

    private void waitForInit(String name) {
        long deadline = System.currentTimeMillis() + INIT_TIMEOUT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            // Check for success marker
            var doneCheck = docker.run(List.of("exec", name, "test", "-f", "/tmp/smithy-init-done"));
            if (doneCheck.exitCode() == 0) {
                log.info("Container {} init completed successfully", name);
                return;
            }

            // Check for failure marker
            var failCheck = docker.run(List.of("exec", name, "test", "-f", "/tmp/smithy-init-failed"));
            if (failCheck.exitCode() == 0) {
                String logs = fetchLogs(name, 50);
                throw new RuntimeException("Container " + name + " init failed. Logs:\n" + logs);
            }

            // Check if container is still running
            var inspectResult = docker.run(List.of("inspect", "--format", "{{.State.Running}}", name));
            if (inspectResult.exitCode() != 0 || !"true".equals(inspectResult.stdout().strip())) {
                String logs = fetchLogs(name, 50);
                throw new RuntimeException("Container " + name + " stopped during init. Logs:\n" + logs);
            }

            try {
                Thread.sleep(INIT_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for container " + name + " init", e);
            }
        }
        log.warn("Container {} init did not complete within {}s — proceeding anyway", name, INIT_TIMEOUT_SECONDS);
    }

    public String fetchLogs(String name, int tailLines) {
        var result = docker.run(List.of("logs", "--tail", String.valueOf(tailLines), name));
        String logs = result.stdout();
        if (result.stderr() != null && !result.stderr().isBlank()) {
            logs += "\n" + result.stderr();
        }
        return logs;
    }

    public String fetchOwnLogs(int tailLines) {
        String selfId = System.getenv("HOSTNAME");
        if (selfId == null || selfId.isBlank()) {
            return "Unable to determine own container id (HOSTNAME not set)";
        }
        return fetchLogs(selfId, tailLines);
    }

    /**
     * Reads the Claude Code session transcript (JSONL) for a given session id.
     * Requires the container to be running, since it shells in to read the file.
     */
    public String fetchSessionTranscript(String containerName, String sessionId) {
        var result = docker.run(
            List.of(
                "exec",
                containerName,
                "sh",
                "-c",
                "cat \"$(find /root/.claude/projects -name '" +
                    sessionId +
                    ".jsonl' 2>/dev/null | head -1)\" 2>/dev/null"
            )
        );
        return result.stdout();
    }

    void destroy(String containerName) {
        docker.run(List.of("stop", containerName));
        docker.run(List.of("rm", "-f", containerName));
        log.info("Destroyed container {}", containerName);
    }

    // ── Exec ─────────────────────────────────────────────────

    ExecResult exec(
        String containerName,
        List<String> command,
        Map<String, String> environment,
        Duration timeout,
        String stdinInput
    ) {
        var args = new ArrayList<String>();
        args.add("exec");

        if (stdinInput != null) {
            args.add("-i");
        }

        args.add("-w");
        args.add("/workspace");

        if (environment != null) {
            environment.forEach((k, v) -> {
                args.add("-e");
                args.add(k + "=" + v);
            });
        }

        args.add(containerName);
        args.addAll(command);

        byte[] stdin = stdinInput != null ? stdinInput.getBytes(StandardCharsets.UTF_8) : null;
        return docker.run(args, stdin, timeout);
    }

    // ── State ────────────────────────────────────────────────

    ContainerState readState(String containerName) {
        try {
            byte[] data = copyFromContainer(containerName, STATE_PATH);
            return MAPPER.readValue(data, ContainerState.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read state from: " + containerName, e);
        }
    }

    void writeState(String containerName, ContainerState state) {
        try {
            byte[] data = MAPPER.writeValueAsBytes(state);
            copyToContainer(containerName, "/tmp", data, "smithy-state.json");
        } catch (Exception e) {
            throw new RuntimeException("Failed to write state to: " + containerName, e);
        }
    }

    // ── File transfer ────────────────────────────────────────

    byte[] copyFromContainer(String containerName, String path) {
        return docker.runForBytes(List.of("exec", containerName, "cat", path), Duration.ofSeconds(30));
    }

    void copyToContainer(String containerName, String destDir, byte[] data, String filename) {
        // Shell-quote the path to prevent injection
        String safePath = destDir + "/" + filename;
        var result = docker.run(
            List.of("exec", "-i", containerName, "sh", "-c", "cat > '" + safePath.replace("'", "'\\''") + "'"),
            data,
            Duration.ofSeconds(30)
        );
        if (result.exitCode() != 0) {
            throw new RuntimeException("Failed to copy to " + containerName + ":" + safePath + ": " + result.stderr());
        }
    }
}
