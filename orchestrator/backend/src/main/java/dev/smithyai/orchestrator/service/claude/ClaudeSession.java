package dev.smithyai.orchestrator.service.claude;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.KnowledgebaseConfig;
import dev.smithyai.orchestrator.service.claude.dto.SchemaGenerator;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClaudeSession {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLAUDE_BINARY = "/usr/bin/claude";
    private static final String PLANS_DIR = "/root/.claude/plans";

    /**
     * Wall-clock budget for one agent turn. A build turn on a real repository
     * works for tens of minutes, so this is deliberately generous; override it
     * with agent.claude.turnTimeout when a workflow needs longer still.
     */
    private static final Duration DEFAULT_TURN_TIMEOUT = Duration.ofMinutes(60);

    /**
     * Head-room on top of the budget before the local docker client stops
     * waiting. The deadline is enforced inside the container, so the client only
     * has to outlive it — this is a backstop for a wedged daemon, not the limit.
     */
    private static final Duration CLIENT_GRACE = Duration.ofSeconds(60);

    /** Grace between SIGTERM and SIGKILL for a turn that overran. */
    private static final String KILL_AFTER = "10s";

    /** GNU timeout's exit status for a command it had to stop. */
    private static final int TIMEOUT_EXIT_CODE = 124;

    private static volatile Duration turnTimeout = DEFAULT_TURN_TIMEOUT;

    /**
     * Set once at startup from agent.claude.turnTimeout by
     * {@link dev.smithyai.orchestrator.config.ConfigLoader}.
     */
    public static void configureTurnTimeout(Duration timeout) {
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            turnTimeout = timeout;
        }
    }

    public static Duration turnTimeout() {
        return turnTimeout;
    }

    /**
     * Model used when callers don't pass one explicitly. Set once at startup from
     * claude.model (CLAUDE_MODEL) by {@link dev.smithyai.orchestrator.config.ConfigLoader}.
     */
    private static volatile String defaultModel = "opus";

    public static void configureDefaultModel(String model) {
        if (model != null && !model.isBlank()) {
            defaultModel = model;
        }
    }

    @Getter
    private final String sessionId;

    private final ContainerSession container;
    private final List<String> tools;
    private final KnowledgebaseConfig knowledgebaseConfig;
    private String contextRepoName;
    private String model;
    private List<String> addDirs = List.of();
    private boolean started = false;
    private Duration sessionTurnTimeout;

    public ClaudeSession(ContainerSession container, List<String> tools) {
        this(container, tools, null, null);
    }

    public ClaudeSession(ContainerSession container, List<String> tools, String existingSessionId) {
        this(container, tools, existingSessionId, null);
    }

    public ClaudeSession(ContainerSession container, List<String> tools, KnowledgebaseConfig knowledgebaseConfig) {
        this(container, tools, null, knowledgebaseConfig);
    }

    public ClaudeSession(
        ContainerSession container,
        List<String> tools,
        String existingSessionId,
        KnowledgebaseConfig knowledgebaseConfig
    ) {
        this.sessionId = existingSessionId != null ? existingSessionId : UUID.randomUUID().toString();
        this.container = container;
        this.tools = tools;
        this.knowledgebaseConfig = knowledgebaseConfig;
        this.started = existingSessionId != null;
    }

    public void setContextRepoName(String contextRepoName) {
        this.contextRepoName = contextRepoName;
    }

    /** Run this session's turns on {@code model} instead of the configured default. Blank keeps the default. */
    public void setModel(String model) {
        if (model != null && !model.isBlank()) {
            this.model = model;
        }
    }

    /**
     * Directories outside the workspace the agent may read and edit —
     * a context repo, extra checkouts. Claude scopes file access to its
     * working directory; anything beyond it must be named per turn or every
     * access is a permission prompt, which headless runs auto-deny.
     */
    public void setAddDirs(List<String> dirs) {
        this.addDirs = dirs == null ? List.of() : List.copyOf(dirs);
    }

    /**
     * Give this session's turns a different budget from the configured one — a
     * turn someone is waiting on in a browser is not a build turn. Null or
     * non-positive keeps the configured budget.
     */
    public void setTurnTimeout(Duration timeout) {
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            this.sessionTurnTimeout = timeout;
        }
    }

    private String model() {
        return model != null ? model : defaultModel;
    }

    private Duration budget() {
        return sessionTurnTimeout != null ? sessionTurnTimeout : turnTimeout;
    }

    public void startPlan(String prompt) {
        execute(prompt, model(), "plan", false, null);
        started = true;
    }

    public String send(String prompt) {
        return send(prompt, String.class);
    }

    public <T> T send(String prompt, Class<T> resultType) {
        return send(prompt, resultType, model());
    }

    public <T> T send(String prompt, Class<T> resultType, String model) {
        boolean resume = started;
        if (!started) started = true;

        String schema = null;
        if (!resultType.equals(String.class)) {
            schema = SchemaGenerator.generate(resultType);
        }

        String content = execute(prompt, model, "default", resume, schema);

        if (resultType.equals(String.class)) {
            return resultType.cast(content);
        }

        try {
            return MAPPER.readValue(content.strip(), resultType);
        } catch (Exception e) {
            throw new ClaudeParseException(
                "Failed to parse Claude output as " + resultType.getSimpleName(),
                content,
                e
            );
        }
    }

    /**
     * Ask for a structured answer against a schema built at runtime.
     *
     * <p>{@link #send(String, Class)} generates its schema from a Java DTO, which
     * a YAML workflow cannot name. A definition declares the shape it wants
     * instead, and gets back the parsed object.
     */
    public Map<String, Object> sendStructured(String prompt, String jsonSchema) {
        boolean resume = started;
        started = true;
        String content = execute(prompt, model(), "default", resume, jsonSchema);
        try {
            return MAPPER.readValue(content.strip(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new ClaudeParseException("Failed to parse Claude output against the declared schema", content, e);
        }
    }

    public void ensureCommitted() {
        String status = container.exec("sh", "-c", "git status --porcelain").stdout().strip();
        if (status.isEmpty()) return;

        log.info("Uncommitted changes in {}, asking Claude to commit", container.getContainerName());
        send("Please commit all changes with an appropriate commit message.");
    }

    /**
     * Returns the path of the latest plan file inside the container,
     * or empty if none exists.
     */
    public Optional<String> latestPlanFile() {
        try {
            String listing = container
                .exec("sh", "-c", "ls -t \"" + PLANS_DIR + "\"/*.md 2>/dev/null")
                .stdout()
                .strip();
            return listing
                .lines()
                .findFirst()
                .map(String::strip)
                .filter(p -> !p.isBlank());
        } catch (Exception e) {
            log.warn("No plan files found in {} on {}", PLANS_DIR, container.getContainerName());
            return Optional.empty();
        }
    }

    // ── Internal ─────────────────────────────────────────────

    private String execute(String prompt, String model, String permissionMode, boolean resume, String outputSchema) {
        Duration budget = budget();

        List<String> command = new ArrayList<>();
        // Bound the turn inside the container rather than only here. Killing the
        // local `docker exec` leaves the CLI running: the daemon does not stop an
        // exec whose client went away, and the orphan keeps working and keeps
        // writing the session transcript that the next turn resumes.
        command.add("timeout");
        command.add("--kill-after=" + KILL_AFTER);
        command.add(budget.toSeconds() + "s");
        command.add(CLAUDE_BINARY);
        command.add("-p");
        command.add("-"); // read prompt from stdin
        command.add("--model");
        command.add(model);
        command.add("--output-format");
        command.add("json");
        if (resume) {
            command.add("--resume");
            command.add(sessionId);
        } else {
            command.add("--session-id");
            command.add(sessionId);
        }
        command.add("--permission-mode");
        command.add(permissionMode);
        command.add("--max-turns");
        command.add("200");

        if (tools != null && !tools.isEmpty()) {
            command.add("--allowedTools");
            command.add(String.join(",", tools));
        }

        for (String dir : addDirs) {
            command.add("--add-dir");
            command.add(dir);
        }

        if (outputSchema != null) {
            command.add("--json-schema");
            command.add(outputSchema);
        }

        if (knowledgebaseConfig != null && knowledgebaseConfig.isActive() && contextRepoName != null) {
            log.info("Adding knowledgebase MCP config for context repo: {}", contextRepoName);
            command.add("--mcp-config");
            command.add(knowledgebaseConfig.mcpConfigJson(contextRepoName));
        } else {
            log.debug(
                "Knowledgebase MCP not added: config={}, active={}, contextRepo={}",
                knowledgebaseConfig != null,
                knowledgebaseConfig != null && knowledgebaseConfig.isActive(),
                contextRepoName
            );
        }

        log.debug("Executing Claude prompt on {} (session={})", container.getContainerName(), sessionId);
        ExecResult result = container.exec(command, budget.plus(CLIENT_GRACE), prompt);

        if (result.exitCode() == TIMEOUT_EXIT_CODE) {
            log.warn(
                "Claude turn timed out after {} on {} (session={})",
                budget,
                container.getContainerName(),
                sessionId
            );
            throw new ClaudeTimeoutException(budget, container.getContainerName(), sessionId, result.stdout());
        }

        if (result.exitCode() != 0) {
            log.warn(
                "Claude process failed: exitCode={}, stderr={}, stdout={}",
                result.exitCode(),
                result.stderr(),
                result.stdout().length() > 500 ? result.stdout().substring(0, 500) + "..." : result.stdout()
            );
            throw new IllegalStateException(
                "Claude process exited with code %d: %s".formatted(result.exitCode(), result.stderr())
            );
        }

        log.debug("Claude response on {}: {}", container.getContainerName(), result.stdout());

        try {
            JsonNode root = MAPPER.readTree(result.stdout());
            // When --json-schema is used, structured output is in "structured_output", not "result"
            if (outputSchema != null && root.has("structured_output")) {
                return MAPPER.writeValueAsString(root.get("structured_output"));
            }
            if (root.has("result")) {
                return root.get("result").asText();
            }
            throw new IllegalStateException("Claude JSON response missing 'result' field: " + result.stdout());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Claude JSON response: " + result.stdout(), e);
        }
    }
}
