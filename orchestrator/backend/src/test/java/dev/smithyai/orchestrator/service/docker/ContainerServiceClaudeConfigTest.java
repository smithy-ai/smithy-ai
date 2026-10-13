package dev.smithyai.orchestrator.service.docker;

import static org.junit.jupiter.api.Assertions.*;

import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.ClaudeConfig;
import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import dev.smithyai.orchestrator.testing.FakeDockerCli;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The operator's skills and subagents reach the task container, or the
 * deployment says why not.
 */
class ContainerServiceClaudeConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void copiesTheConfiguredDirectoryIntoTheContainersClaudeHome() throws Exception {
        Path skills = Files.createDirectories(tempDir.resolve("claude/skills/persona-financing"));
        Files.writeString(skills.resolve("SKILL.md"), "---\nname: persona-financing\n---\n");
        var docker = new FakeDockerCli();

        service(docker, tempDir.resolve("claude").toString()).installClaudeConfig("box");

        assertEquals(
            List.of(
                List.of("exec", "box", "mkdir", "-p", "/root/.claude"),
                List.of("cp", tempDir.resolve("claude") + "/.", "box:/root/.claude")
            ),
            docker.invocations
        );
    }

    @Test
    void copiesNothingWhenNoDirectoryIsConfigured() {
        var docker = new FakeDockerCli();

        service(docker, "").installClaudeConfig("box");
        service(docker, null).installClaudeConfig("box");

        assertTrue(docker.invocations.isEmpty(), "unexpected docker calls: " + docker.invocations);
    }

    @Test
    void aMissingDirectoryIsAWarningNotAFailure() {
        var docker = new FakeDockerCli();

        service(docker, tempDir.resolve("nowhere").toString()).installClaudeConfig("box");

        assertTrue(docker.invocations.isEmpty(), "unexpected docker calls: " + docker.invocations);
    }

    @Test
    void aFailedCopyFailsTheContainerRatherThanSilentlyShippingWithoutSkills() throws Exception {
        Files.createDirectories(tempDir.resolve("claude"));
        var docker = new FakeDockerCli() {
            @Override
            public ExecResult run(List<String> args) {
                var result = super.run(args);
                return "cp".equals(args.getFirst()) ? new ExecResult(1, "", "no space left") : result;
            }
        };

        var error = assertThrows(RuntimeException.class, () ->
            service(docker, tempDir.resolve("claude").toString()).installClaudeConfig("box")
        );

        assertTrue(error.getMessage().contains("no space left"), error.getMessage());
    }

    private static ContainerService service(DockerCli docker, String claudeConfigDir) {
        return new ContainerService(
            new DockerConfig("docker", "smithy-net", "claude-task:test", null, claudeConfigDir),
            new ClaudeConfig("test-token", null, "claude-opus-5"),
            new VcsProviderConfig(
                "forgejo",
                null,
                new VcsProviderConfig.ForgejoProviderConfig(
                    "http://forgejo.invalid",
                    "http://forgejo.invalid",
                    null,
                    "smithy-token",
                    "architect-token",
                    "coordinator-token"
                ),
                null,
                null,
                null
            ),
            new BotConfig(
                new BotConfig.BotEntry("smithy", "smithy@localhost"),
                new BotConfig.BotEntry("architect", "architect@localhost"),
                new BotConfig.BotEntry("coordinator", "coordinator@localhost")
            ),
            docker
        );
    }
}
