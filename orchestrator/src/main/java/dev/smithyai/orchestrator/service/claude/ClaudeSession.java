package dev.smithyai.orchestrator.service.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.KnowledgebaseConfig;
import dev.smithyai.orchestrator.service.claude.dto.SchemaGenerator;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClaudeSession {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofMinutes(30);
    private static final String CLAUDE_BINARY = "/usr/bin/claude";
    private static final String MODEL = "opus";
    private static final String PLANS_DIR = "/root/.claude/plans";

    @Getter
    private final String sessionId;

    private final ContainerSession container;
    private final List<String> tools;
    private final KnowledgebaseConfig knowledgebaseConfig;
    private boolean started = false;

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

    public void startPlan(String prompt) {
        execute(prompt, "plan", false, null);
        started = true;
    }

    public String send(String prompt) {
        return send(prompt, String.class);
    }

    public <T> T send(String prompt, Class<T> resultType) {
        boolean resume = started;
        if (!started) started = true;

        String schema = null;
        if (!resultType.equals(String.class)) {
            schema = SchemaGenerator.generate(resultType);
        }

        String content = execute(prompt, "default", resume, schema);

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

    private String execute(String prompt, String permissionMode, boolean resume, String outputSchema) {
        List<String> command = new ArrayList<>();
        command.add(CLAUDE_BINARY);
        command.add("-p");
        command.add("-"); // read prompt from stdin
        command.add("--model");
        command.add(MODEL);
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

        if (outputSchema != null) {
            command.add("--json-schema");
            command.add(outputSchema);
        }

        if (knowledgebaseConfig != null && knowledgebaseConfig.isActive()) {
            command.add("--mcp-config");
            command.add(knowledgebaseConfig.mcpConfigJson());
        }

        log.debug("Executing Claude prompt on {} (session={})", container.getContainerName(), sessionId);
        ExecResult result = container.exec(command, TIMEOUT, prompt);

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
