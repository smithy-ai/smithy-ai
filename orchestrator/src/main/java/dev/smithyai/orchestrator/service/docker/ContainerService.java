package dev.smithyai.orchestrator.service.docker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.smithyai.orchestrator.config.ClaudeConfig;
import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerState;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
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
    private final String gitAuthUser;

    public ContainerService(
        DockerConfig dockerConfig,
        ClaudeConfig claudeConfig,
        VcsProviderConfig vcsConfig,
        DockerCli docker
    ) {
        this.docker = docker;
        this.network = dockerConfig.network();
        this.taskImage = dockerConfig.taskImage();
        this.vcsUrl = vcsConfig.resolvedUrl();
        this.vcsToken = vcsConfig.smithyToken();
        this.claudeOauthToken = claudeConfig.oauthToken();
        this.gitAuthUser = vcsConfig.gitAuthUser();
    }

    // ── Public API ───────────────────────────────────────────

    public ContainerSession createSession(String name) {
        return new ContainerSession(name, this);
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

        // Labels
        args.add("--label");
        args.add("smithy.managed=true");
        if (init.workflowType() != null) {
            args.add("--label");
            args.add("smithy.workflow=" + init.workflowType().value());
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
        args.add("-e");
        args.add("CLAUDE_CODE_OAUTH_TOKEN=" + claudeOauthToken);
        args.add("-e");
        args.add("VCS_URL=" + vcsUrl);
        args.add("-e");
        args.add("VCS_TOKEN=" + (init.vcsToken() != null ? init.vcsToken() : vcsToken));
        args.add("-e");
        args.add("CLONE_URL=" + init.cloneUrl());
        args.add("-e");
        args.add("BRANCH=" + init.branch());
        args.add("-e");
        args.add("SOURCE_BRANCH=" + (init.sourceBranch() != null ? init.sourceBranch() : ""));
        args.add("-e");
        args.add("GIT_EMAIL=" + (init.gitEmail() != null ? init.gitEmail() : "smithy@localhost"));
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
                log.warn("Failed to serialize extra repos", e);
            }
        }
        args.add("-e");
        args.add("GIT_AUTH_USER=" + gitAuthUser);
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
                String logs = fetchLogs(name);
                throw new RuntimeException("Container " + name + " init failed. Logs:\n" + logs);
            }

            // Check if container is still running
            var inspectResult = docker.run(List.of("inspect", "--format", "{{.State.Running}}", name));
            if (inspectResult.exitCode() != 0 || !"true".equals(inspectResult.stdout().strip())) {
                String logs = fetchLogs(name);
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

    private String fetchLogs(String name) {
        var result = docker.run(List.of("logs", "--tail", "50", name));
        String logs = result.stdout();
        if (result.stderr() != null && !result.stderr().isBlank()) {
            logs += "\n" + result.stderr();
        }
        return logs;
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
