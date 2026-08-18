package dev.smithyai.orchestrator.runtime.actions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.ClaudeConfig;
import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.service.claude.ClaudeSession;
import dev.smithyai.orchestrator.service.claude.PromptRenderer;
import dev.smithyai.orchestrator.service.docker.ContainerService;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import dev.smithyai.orchestrator.testing.FakeDockerCli;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * The optional {@code model:} input on agent turns.
 *
 * <p>A definition that routes with a small model and builds with a large one
 * says so per step; a step that says nothing runs on the configured default.
 * The fake replaces the process boundary only, so what is asserted is the
 * {@code --model} flag the real command construction produced.
 */
class AgentModelInputTest {

    private static final ActionContext CONTEXT = new ActionContext(null, null, Map.of(), Map.of());

    private FakeDockerCli docker;
    private RunEnvironments environments;
    private PromptRenderer prompts;

    @BeforeEach
    void setUp() {
        docker = new FakeDockerCli();
        var containers = new ContainerService(dockerConfig(), claudeConfig(), vcsProviderConfig(), botConfig(), docker);
        var container = new ContainerSession("agent-model-test", containers);
        var agent = new ClaudeSession(container, List.of());

        environments = mock(RunEnvironments.class);
        when(environments.agent(any(), any())).thenReturn(agent);
        when(environments.newAgent(any(), any())).thenReturn(agent);
        when(environments.container(any())).thenReturn(container);
    }

    @AfterEach
    void restoreDefaultModel() {
        // The default is static; put back the shipped value so no other test
        // inherits what this one configured.
        ClaudeSession.configureDefaultModel("opus");
    }

    @Test
    void theModelInputOverridesTheConfiguredModelForThatTurn() {
        runAction().execute(CONTEXT, Map.of("prompt", "hi", "model", "haiku"));

        assertEquals("haiku", modelFlag());
    }

    @Test
    void aTurnWithoutAModelInputRunsOnTheConfiguredModel() {
        ClaudeSession.configureDefaultModel("configured-model");

        runAction().execute(CONTEXT, Map.of("prompt", "hi"));

        assertEquals("configured-model", modelFlag());
    }

    @Test
    void aBlankModelMeansTheConfiguredModelNotAModelNamedNothing() {
        // What "{{ vars.buildModel }}" renders to when the variable is unset.
        ClaudeSession.configureDefaultModel("configured-model");

        runAction().execute(CONTEXT, Map.of("prompt", "hi", "model", ""));

        assertEquals("configured-model", modelFlag());
    }

    @Test
    void aPlanTurnHonoursTheModelInput() {
        runAction().execute(CONTEXT, Map.of("prompt", "hi", "mode", "plan", "model", "haiku"));

        assertEquals("haiku", modelFlag());
    }

    @Test
    void aStructuredTurnHonoursTheModelInput() {
        docker.enqueueClaudeStructured("{\"answer\": \"yes\"}");

        new AgentRunStructuredAction(environments, prompts()).execute(
            CONTEXT,
            Map.of("prompt", "hi", "model", "haiku", "output", Map.of("answer", "string"))
        );

        assertEquals("haiku", modelFlag());
    }

    // ── Plumbing ─────────────────────────────────────────────

    private AgentRunAction runAction() {
        return new AgentRunAction(environments, prompts());
    }

    private PromptRenderer prompts() {
        if (prompts == null) prompts = new PromptRenderer(new DefaultResourceLoader());
        return prompts;
    }

    /** The value after {@code --model} in the claude invocation the fake saw. */
    private String modelFlag() {
        for (var args : docker.invocations) {
            if (!args.contains("/usr/bin/claude")) continue;
            int i = args.indexOf("--model");
            assertTrue(i >= 0 && i + 1 < args.size(), "claude was invoked without --model: " + args);
            return args.get(i + 1);
        }
        fail("no claude invocation reached the fake: " + docker.invocations);
        return null;
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
