package dev.smithyai.orchestrator.service.claude;

import static org.junit.jupiter.api.Assertions.*;

import dev.smithyai.orchestrator.config.AgentConfig.ClaudeAgentConfig;
import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.ClaudeConfig;
import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.service.docker.ContainerService;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import dev.smithyai.orchestrator.testing.FakeDockerCli;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The wall-clock budget on an agent turn.
 *
 * <p>The deadline has to be enforced inside the container: the docker daemon does
 * not stop an exec whose client went away, so a timeout applied only on this side
 * leaves the CLI running, still working and still writing the transcript the next
 * turn resumes. What is asserted is therefore the {@code timeout} prefix on the
 * command the fake saw, not just the failure the caller gets.
 */
class ClaudeTurnTimeoutTest {

    private FakeDockerCli docker;
    private ContainerSession container;

    @BeforeEach
    void setUp() {
        docker = new FakeDockerCli();
        var containers = new ContainerService(dockerConfig(), claudeConfig(), vcsProviderConfig(), botConfig(), docker);
        container = new ContainerSession("timeout-test", containers);
    }

    @AfterEach
    void restoreDefaultTimeout() {
        // The budget is static; put back the shipped value so no other test
        // inherits what this one configured.
        ClaudeSession.configureTurnTimeout(Duration.ofMinutes(60));
    }

    @Test
    void theTurnIsBoundedInsideTheContainer() {
        ClaudeSession.configureTurnTimeout(Duration.ofMinutes(45));

        new ClaudeSession(container, List.of()).send("hi");

        var args = claudeInvocation();
        int i = args.indexOf("timeout");
        assertTrue(i >= 0, "claude was not run under timeout: " + args);
        assertEquals("--kill-after=10s", args.get(i + 1));
        assertEquals("2700s", args.get(i + 2));
        assertEquals("/usr/bin/claude", args.get(i + 3));
    }

    @Test
    void aTurnThatOverrunsFailsWithItsBudgetAndTheKeyThatRaisesIt() {
        ClaudeSession.configureTurnTimeout(Duration.ofMinutes(45));
        // What GNU timeout returns for a command it had to stop.
        docker.onExec("/usr/bin/claude", new ExecResult(124, "", ""));

        var session = new ClaudeSession(container, List.of());
        var thrown = assertThrows(ClaudeTimeoutException.class, () -> session.send("hi"));

        assertEquals(Duration.ofMinutes(45), thrown.getBudget());
        assertEquals("timeout-test", thrown.getContainerName());
        assertTrue(thrown.getMessage().contains("45m"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("agent.claude.turnTimeout"), thrown.getMessage());
    }

    @Test
    void aTurnThatFailsForAnotherReasonIsNotReportedAsATimeout() {
        docker.onExec("/usr/bin/claude", new ExecResult(1, "", "Invalid API key"));

        var session = new ClaudeSession(container, List.of());
        var thrown = assertThrows(IllegalStateException.class, () -> session.send("hi"));

        assertFalse(thrown instanceof ClaudeTimeoutException, thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Invalid API key"), thrown.getMessage());
    }

    @Test
    void anUnsetTurnTimeoutKeepsTheBuiltInDefault() {
        assertTrue(agentConfig(null).resolvedTurnTimeout().isEmpty());
        assertTrue(agentConfig("  ").resolvedTurnTimeout().isEmpty());
    }

    @Test
    void aConfiguredTurnTimeoutAcceptsFriendlyAndIsoDurations() {
        assertEquals(Duration.ofMinutes(45), agentConfig("45m").resolvedTurnTimeout().orElseThrow());
        assertEquals(Duration.ofHours(2), agentConfig("2h").resolvedTurnTimeout().orElseThrow());
        assertEquals(Duration.ofSeconds(900), agentConfig("900s").resolvedTurnTimeout().orElseThrow());
        assertEquals(Duration.ofMinutes(45), agentConfig("PT45M").resolvedTurnTimeout().orElseThrow());
    }

    @Test
    void aTurnTimeoutThatIsNotADurationFailsAtStartupNamingTheKey() {
        var thrown = assertThrows(IllegalStateException.class, () -> agentConfig("soon").resolvedTurnTimeout());

        assertTrue(thrown.getMessage().contains("agent.claude.turnTimeout"), thrown.getMessage());
    }

    @Test
    void aNonPositiveTurnTimeoutIsRejectedRatherThanKillingEveryTurn() {
        assertThrows(IllegalStateException.class, () -> agentConfig("0m").resolvedTurnTimeout());
        assertThrows(IllegalStateException.class, () -> agentConfig("-5m").resolvedTurnTimeout());
    }

    // ── Plumbing ─────────────────────────────────────────────

    private static ClaudeAgentConfig agentConfig(String turnTimeout) {
        return new ClaudeAgentConfig("claude-opus-5", null, null, turnTimeout, null);
    }

    private List<String> claudeInvocation() {
        return docker.invocations
            .stream()
            .filter(args -> args.contains("/usr/bin/claude"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no claude invocation reached the fake: " + docker.invocations));
    }

    private static DockerConfig dockerConfig() {
        return new DockerConfig("docker", "smithy-net", "claude-task:test", null);
    }

    private static ClaudeConfig claudeConfig() {
        return new ClaudeConfig("test-token", null, "claude-opus-5");
    }

    private static BotConfig botConfig() {
        return new BotConfig(
            new BotConfig.BotEntry("smithy", "smithy@localhost"),
            new BotConfig.BotEntry("architect", "architect@localhost"),
            new BotConfig.BotEntry("coordinator", "coordinator@localhost")
        );
    }

    private static VcsProviderConfig vcsProviderConfig() {
        return new VcsProviderConfig(
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
        );
    }
}
