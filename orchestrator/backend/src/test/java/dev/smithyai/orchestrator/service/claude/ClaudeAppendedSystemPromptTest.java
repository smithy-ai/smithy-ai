package dev.smithyai.orchestrator.service.claude;

import static org.junit.jupiter.api.Assertions.*;

import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.ClaudeConfig;
import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.service.docker.ContainerService;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import dev.smithyai.orchestrator.testing.FakeDockerCli;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A human-driven turn carries its own system prompt; a workflow turn does not. */
class ClaudeAppendedSystemPromptTest {

    @Test
    void theAppendedPromptReachesTheCommandLineOnlyWhenSet() {
        var docker = new FakeDockerCli();
        var container = new ContainerSession(
            "prompt-test",
            new ContainerService(
                new DockerConfig("docker", "smithy-net", "claude-task:test", null),
                new ClaudeConfig("test-token", null, "claude-opus-5"),
                new VcsProviderConfig(
                    "forgejo",
                    null,
                    new VcsProviderConfig.ForgejoProviderConfig(
                        "http://forgejo.invalid",
                        "http://forgejo.invalid",
                        null,
                        "t",
                        "t",
                        "t"
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
            )
        );

        var steered = new ClaudeSession(container, List.of("Read"));
        steered.setAppendedSystemPrompt("You are being steered.");
        steered.send("hello");
        var first = claudeInvocation(docker, 0);
        int flag = first.indexOf("--append-system-prompt");
        assertTrue(flag > 0, "flag missing: " + first);
        assertEquals("You are being steered.", first.get(flag + 1));

        var plain = new ClaudeSession(container, List.of("Read"));
        plain.send("hello");
        assertFalse(claudeInvocation(docker, 1).contains("--append-system-prompt"));
    }

    private static List<String> claudeInvocation(FakeDockerCli docker, int index) {
        return docker.invocations
            .stream()
            .filter(args -> args.contains("/usr/bin/claude"))
            .toList()
            .get(index);
    }
}
