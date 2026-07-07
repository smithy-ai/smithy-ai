package dev.smithyai.orchestrator.service.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.CodexConfig;
import dev.smithyai.orchestrator.config.KnowledgebaseConfig;
import dev.smithyai.orchestrator.service.agent.AgentSession;
import dev.smithyai.orchestrator.service.claude.ClaudeParseException;
import dev.smithyai.orchestrator.service.claude.dto.SchemaGenerator;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CodexSession implements AgentSession {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofMinutes(30);
    private static final String CODEX_BINARY = "/usr/bin/codex";
    private static final String WORKDIR = "/workspace";
    private static final String RUN_DIR = "/tmp/smithy-codex";
    private static final String PLANS_DIR = RUN_DIR + "/plans";
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );

    @Getter
    private String sessionId;

    private final ContainerSession container;
    private final KnowledgebaseConfig knowledgebaseConfig;
    private final CodexConfig codexConfig;
    private String contextRepoName;
    private String lastPlanFile;
    private boolean started = false;

    public CodexSession(
        ContainerSession container,
        List<String> tools,
        String existingSessionId,
        KnowledgebaseConfig knowledgebaseConfig,
        CodexConfig codexConfig
    ) {
        this.sessionId = blankToNull(existingSessionId);
        this.container = container;
        this.knowledgebaseConfig = knowledgebaseConfig;
        this.codexConfig = codexConfig;
        this.started = this.sessionId != null;
    }

    @Override
    public void setContextRepoName(String contextRepoName) {
        this.contextRepoName = contextRepoName;
    }

    @Override
    public void startPlan(String prompt) {
        String plan = execute(
            prompt + "\n\nReturn only the development plan in Markdown. Do not modify files during planning.",
            null,
            null
        );
        String planPath = PLANS_DIR + "/" + UUID.randomUUID() + ".md";
        writeFile(planPath, plan);
        lastPlanFile = planPath;
    }

    @Override
    public String send(String prompt) {
        return send(prompt, String.class);
    }

    @Override
    public <T> T send(String prompt, Class<T> resultType) {
        return send(prompt, resultType, null);
    }

    @Override
    public <T> T send(String prompt, Class<T> resultType, String model) {
        String schema = null;
        if (!resultType.equals(String.class)) {
            schema = SchemaGenerator.generate(resultType);
        }

        String content = execute(prompt, resolveModel(model), schema);

        if (resultType.equals(String.class)) {
            return resultType.cast(content);
        }

        try {
            return MAPPER.readValue(content.strip(), resultType);
        } catch (Exception e) {
            throw new ClaudeParseException("Failed to parse Codex output as " + resultType.getSimpleName(), content, e);
        }
    }

    @Override
    public void ensureCommitted() {
        String status = container.exec("sh", "-c", "git status --porcelain").stdout().strip();
        if (status.isEmpty()) return;

        log.info("Uncommitted changes in {}, asking Codex to commit", container.getContainerName());
        send("Please commit all changes with an appropriate commit message.");
    }

    @Override
    public Optional<String> latestPlanFile() {
        return Optional.ofNullable(lastPlanFile);
    }

    private String execute(String prompt, String model, String outputSchema) {
        ensureRunDir();

        String executionId = UUID.randomUUID().toString();
        String outputPath = RUN_DIR + "/last-message-" + executionId + ".txt";
        String schemaPath = null;
        if (outputSchema != null) {
            schemaPath = "schema-" + executionId + ".json";
            container.copyToContainer(RUN_DIR, outputSchema.getBytes(StandardCharsets.UTF_8), schemaPath);
            schemaPath = RUN_DIR + "/" + schemaPath;
        }

        List<String> command = buildCommand(outputPath, schemaPath, model);

        log.debug("Executing Codex prompt on {} (session={})", container.getContainerName(), sessionId);
        ExecResult result = container.exec(command, TIMEOUT, prompt);
        updateSessionId(result.stdout());

        if (result.exitCode() != 0) {
            log.warn(
                "Codex process failed: exitCode={}, stderr={}, stdout={}",
                result.exitCode(),
                result.stderr(),
                result.stdout().length() > 500 ? result.stdout().substring(0, 500) + "..." : result.stdout()
            );
            throw new IllegalStateException(
                "Codex process exited with code %d: %s".formatted(result.exitCode(), result.stderr())
            );
        }

        if (sessionId == null) {
            throw new IllegalStateException("Codex JSONL output did not include a session id");
        }

        started = true;
        return readOutput(outputPath, result);
    }

    private List<String> buildCommand(String outputPath, String schemaPath, String model) {
        var command = new ArrayList<String>();
        command.add(CODEX_BINARY);
        command.add("exec");

        if (started && sessionId != null) {
            command.add("resume");
            addSharedOptions(command, outputPath, schemaPath, model);
            command.add(sessionId);
            command.add("-");
            return command;
        }

        addSharedOptions(command, outputPath, schemaPath, model);
        command.add("--cd");
        command.add(WORKDIR);
        command.add("-");
        return command;
    }

    private void addSharedOptions(List<String> command, String outputPath, String schemaPath, String model) {
        command.add("--json");
        command.add("--dangerously-bypass-approvals-and-sandbox");
        command.add("--output-last-message");
        command.add(outputPath);

        if (model != null) {
            command.add("--model");
            command.add(model);
        }

        if (schemaPath != null) {
            command.add("--output-schema");
            command.add(schemaPath);
        }

        if (knowledgebaseConfig != null && knowledgebaseConfig.isActive() && contextRepoName != null) {
            command.add("-c");
            command.add("mcp_servers.knowledgebase.url=" + tomlString(knowledgebaseConfig.scopedUrl(contextRepoName)));
        }
    }

    private String readOutput(String outputPath, ExecResult codexResult) {
        ExecResult output = container.exec("cat", outputPath);
        if (output.exitCode() != 0) {
            throw new IllegalStateException(
                "Failed to read Codex output file %s after successful execution: %s\nCodex stdout:\n%s".formatted(
                    outputPath,
                    output.stderr(),
                    codexResult.stdout()
                )
            );
        }
        return output.stdout();
    }

    private void writeFile(String path, String content) {
        String dir = path.substring(0, path.lastIndexOf('/'));
        ExecResult mkdir = container.exec("mkdir", "-p", dir);
        if (mkdir.exitCode() != 0) {
            throw new IllegalStateException("Failed to create Codex plan dir " + dir + ": " + mkdir.stderr());
        }

        container.copyToContainer(
            dir,
            content.getBytes(StandardCharsets.UTF_8),
            path.substring(path.lastIndexOf('/') + 1)
        );
    }

    private void ensureRunDir() {
        ExecResult result = container.exec("mkdir", "-p", RUN_DIR, PLANS_DIR);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Failed to create Codex run dir: " + result.stderr());
        }
    }

    private void updateSessionId(String jsonl) {
        jsonl
            .lines()
            .map(this::sessionIdFromLine)
            .flatMap(Optional::stream)
            .reduce((first, second) -> second)
            .ifPresent(id -> sessionId = id);
    }

    private Optional<String> sessionIdFromLine(String line) {
        if (line == null || line.isBlank()) return Optional.empty();
        try {
            return findSessionId(MAPPER.readTree(line));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<String> findSessionId(JsonNode node) {
        if (node == null || node.isNull()) return Optional.empty();
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (field.getKey().toLowerCase().contains("session") && field.getValue().isTextual()) {
                    String value = field.getValue().asText();
                    if (UUID_PATTERN.matcher(value).matches()) {
                        return Optional.of(value);
                    }
                }
                Optional<String> nested = findSessionId(field.getValue());
                if (nested.isPresent()) return nested;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                Optional<String> nested = findSessionId(child);
                if (nested.isPresent()) return nested;
            }
        }
        return Optional.empty();
    }

    private String resolveModel(String requestedModel) {
        if (codexConfig.hasModel()) {
            return codexConfig.model();
        }
        String requested = blankToNull(requestedModel);
        if (requested == null || "opus".equals(requested) || "haiku".equals(requested)) {
            return null;
        }
        return requested;
    }

    private static String tomlString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
