package dev.smithyai.orchestrator.testing;

import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.service.docker.DockerCli;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A simulated Docker daemon.
 *
 * <p>Faking at this level rather than mocking {@code ContainerSession} or
 * {@code ClaudeSession} means the real container-state handling, the real
 * command construction and the real Claude output parsing all still run — the
 * only thing replaced is the process boundary.
 */
public class FakeDockerCli extends DockerCli {

    /** Every argument list this fake was asked to run, in order. */
    public final List<List<String>> invocations = new ArrayList<>();

    private final Set<String> containers = new LinkedHashSet<>();
    private final Set<String> running = new LinkedHashSet<>();
    private final Map<String, Map<String, String>> files = new ConcurrentHashMap<>();

    /** Canned Claude responses, returned in order for successive agent turns. */
    private final Deque<String> claudeResponses = new ArrayDeque<>();

    /** Shell commands to fail, keyed by a substring of the command. */
    private final Map<String, ExecResult> execOverrides = new LinkedHashMap<>();

    public FakeDockerCli() {
        super(new DockerConfig("docker", "net", "img", null));
    }

    // ── Scripting ────────────────────────────────────────────

    /** Queue the {@code result} text of the next Claude turn. */
    public FakeDockerCli enqueueClaudeText(String text) {
        claudeResponses.add("{\"result\": %s}".formatted(quote(text)));
        return this;
    }

    /** Queue a structured Claude turn — the shape --json-schema produces. */
    public FakeDockerCli enqueueClaudeStructured(String rawJson) {
        claudeResponses.add("{\"structured_output\": %s}".formatted(rawJson));
        return this;
    }

    /** Make any exec whose joined command contains {@code match} return {@code result}. */
    public FakeDockerCli onExec(String match, ExecResult result) {
        execOverrides.put(match, result);
        return this;
    }

    public FakeDockerCli withFile(String container, String path, String content) {
        files.computeIfAbsent(container, c -> new ConcurrentHashMap<>()).put(path, content);
        return this;
    }

    public String fileAt(String container, String path) {
        return files.getOrDefault(container, Map.of()).get(path);
    }

    public boolean containerExists(String name) {
        return containers.contains(name);
    }

    // ── DockerCli ────────────────────────────────────────────

    @Override
    public ExecResult run(List<String> args) {
        return run(args, null, null);
    }

    @Override
    public byte[] runForBytes(List<String> args, Duration timeout) {
        return run(args, null, timeout).stdout().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public ExecResult run(List<String> args, byte[] stdin, Duration timeout) {
        invocations.add(List.copyOf(args));
        String verb = args.isEmpty() ? "" : args.getFirst();

        return switch (verb) {
            case "create" -> handleCreate(args);
            case "start" -> {
                running.add(args.getLast());
                yield ok("");
            }
            case "stop" -> {
                running.remove(args.getLast());
                yield ok("");
            }
            case "rm" -> {
                String name = args.getLast();
                containers.remove(name);
                running.remove(name);
                files.remove(name);
                yield ok("");
            }
            case "ps" -> ok(String.join("\n", args.contains("-a") ? containers : running));
            case "inspect" -> handleInspect(args);
            case "logs" -> ok("fake logs");
            case "exec" -> handleExec(args, stdin);
            default -> ok("");
        };
    }

    private ExecResult handleCreate(List<String> args) {
        // The container name follows --name.
        int i = args.indexOf("--name");
        String name = i >= 0 && i + 1 < args.size() ? args.get(i + 1) : "unnamed";
        containers.add(name);
        files.computeIfAbsent(name, c -> new ConcurrentHashMap<>());
        // smithy-init writes this once setup succeeds; the real one is the CMD.
        files.get(name).put("/tmp/smithy-init-done", "");
        return ok(name);
    }

    private ExecResult handleInspect(List<String> args) {
        String name = args.getLast();
        if (!containers.contains(name)) return new ExecResult(1, "", "No such object: " + name);
        if (args.contains("{{.State.Running}}")) return ok(String.valueOf(running.contains(name)));
        return ok("[{}]");
    }

    private ExecResult handleExec(List<String> args, byte[] stdin) {
        // Skip "exec" and any flags to find the container name.
        int idx = 1;
        while (idx < args.size() && args.get(idx).startsWith("-")) idx++;
        if (idx >= args.size()) return ok("");
        String container = args.get(idx);
        List<String> command = args.subList(idx + 1, args.size());
        String joined = String.join(" ", command);

        for (var override : execOverrides.entrySet()) {
            if (joined.contains(override.getKey())) return override.getValue();
        }

        // `test -f <path>` — the init-complete probe.
        if (command.size() == 3 && command.get(0).equals("test") && command.get(1).equals("-f")) {
            boolean exists = files.getOrDefault(container, Map.of()).containsKey(command.get(2));
            return exists ? ok("") : new ExecResult(1, "", "");
        }

        // `cat <path>` — reading container state.
        if (command.size() == 2 && command.get(0).equals("cat")) {
            String content = files.getOrDefault(container, Map.of()).get(command.get(1));
            return content == null ? new ExecResult(1, "", "No such file") : ok(content);
        }

        // `sh -c "cat > '<path>'"` — writing container state.
        if (joined.startsWith("sh -c cat > ") || joined.contains("cat > '")) {
            String path = joined.substring(joined.indexOf("cat > ") + 6).replace("'", "").trim();
            files
                .computeIfAbsent(container, c -> new ConcurrentHashMap<>())
                .put(path, stdin == null ? "" : new String(stdin, StandardCharsets.UTF_8));
            return ok("");
        }

        if (joined.contains("/usr/bin/claude")) {
            String response = claudeResponses.isEmpty() ? "{\"result\": \"ok\"}" : claudeResponses.poll();
            return ok(response);
        }

        // git status --porcelain and friends: a clean tree keeps flows moving.
        return ok("");
    }

    private static ExecResult ok(String stdout) {
        return new ExecResult(0, stdout, "");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
