package dev.smithyai.orchestrator.service.docker;

import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DockerCli {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final String dockerCommand;

    public DockerCli(DockerConfig config) {
        this.dockerCommand = config.command() != null ? config.command() : "docker";
    }

    public ExecResult run(List<String> args, byte[] stdin, Duration timeout) {
        var command = new ArrayList<String>();
        command.add(dockerCommand);
        command.addAll(args);

        var pb = new ProcessBuilder(command);
        if (stdin != null) {
            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        }

        Duration effectiveTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT;

        try {
            var process = pb.start();

            // Write stdin if provided, then close to send EOF
            if (stdin != null) {
                try (var os = process.getOutputStream()) {
                    os.write(stdin);
                }
            }

            // Read stdout and stderr concurrently to avoid pipe buffer deadlock
            byte[][] stdout = new byte[1][];
            byte[][] stderr = new byte[1][];

            var stdoutReader = Thread.ofVirtual().start(() -> {
                try {
                    stdout[0] = process.getInputStream().readAllBytes();
                } catch (IOException e) {
                    stdout[0] = new byte[0];
                }
            });

            var stderrReader = Thread.ofVirtual().start(() -> {
                try {
                    stderr[0] = process.getErrorStream().readAllBytes();
                } catch (IOException e) {
                    stderr[0] = new byte[0];
                }
            });

            boolean finished = process.waitFor(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                stdoutReader.join(1000);
                stderrReader.join(1000);
                return new ExecResult(
                    124,
                    new String(stdout[0] != null ? stdout[0] : new byte[0], StandardCharsets.UTF_8),
                    "Timed out after " + effectiveTimeout
                );
            }

            stdoutReader.join();
            stderrReader.join();

            return new ExecResult(
                process.exitValue(),
                new String(stdout[0] != null ? stdout[0] : new byte[0], StandardCharsets.UTF_8),
                new String(stderr[0] != null ? stderr[0] : new byte[0], StandardCharsets.UTF_8)
            );
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to run: " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecResult(130, "", "Interrupted");
        }
    }

    public ExecResult run(List<String> args) {
        return run(args, null, null);
    }

    public byte[] runForBytes(List<String> args, Duration timeout) {
        var command = new ArrayList<String>();
        command.add(dockerCommand);
        command.addAll(args);

        var pb = new ProcessBuilder(command);
        Duration effectiveTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT;

        try {
            var process = pb.start();

            byte[][] stdout = new byte[1][];
            byte[][] stderr = new byte[1][];

            var stdoutReader = Thread.ofVirtual().start(() -> {
                try {
                    stdout[0] = process.getInputStream().readAllBytes();
                } catch (IOException e) {
                    stdout[0] = new byte[0];
                }
            });

            var stderrReader = Thread.ofVirtual().start(() -> {
                try {
                    stderr[0] = process.getErrorStream().readAllBytes();
                } catch (IOException e) {
                    stderr[0] = new byte[0];
                }
            });

            boolean finished = process.waitFor(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("docker command timed out after " + effectiveTimeout + ": " + command);
            }

            stdoutReader.join();
            stderrReader.join();

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String stderrStr = new String(stderr[0] != null ? stderr[0] : new byte[0], StandardCharsets.UTF_8);
                throw new RuntimeException(
                    "docker command failed (exit " + exitCode + "): " + command + "\nstderr: " + stderrStr
                );
            }

            return stdout[0] != null ? stdout[0] : new byte[0];
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to run: " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted running: " + command, e);
        }
    }
}
