package dev.smithyai.orchestrator.runtime.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.smithyai.orchestrator.config.AgentConfig.ClaudeAgentConfig;
import dev.smithyai.orchestrator.runtime.engine.RunTakeover.Screenshot;
import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.runtime.store.Run;
import dev.smithyai.orchestrator.runtime.store.RunStatus;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import dev.smithyai.orchestrator.service.claude.ClaudeSession;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * An image attached to a takeover message has to reach the agent, and the agent
 * reads files rather than bytes — so it lands in the container and the prompt
 * names it.
 */
class RunTakeoverScreenshotTest {

    private static final Run RUN = new Run(
        "run-1",
        "smithy",
        "1",
        RunStatus.RUNNING,
        "building",
        Map.of(),
        null,
        null,
        Instant.now(),
        Instant.now(),
        null
    );

    private static final String DIR = "/workspace/.smithy/tmp/takeover";

    private RunStore store;
    private RunEnvironments environments;
    private RunTakeover takeover;
    private ContainerSession container;
    private ClaudeSession agent;

    @BeforeEach
    void setUp() {
        store = mock(RunStore.class);
        environments = mock(RunEnvironments.class);
        container = mock(ContainerSession.class);
        agent = mock(ClaudeSession.class);

        when(store.isLeased(any())).thenReturn(true);
        when(store.acquireLease(any(), any(), any())).thenReturn(Optional.of(Instant.now().plusSeconds(30)));
        when(environments.container(any())).thenReturn(container);
        when(environments.agent(any(), any())).thenReturn(agent);
        when(container.ensureScratchDir(anyString())).thenReturn(true);
        when(container.getContainerName()).thenReturn("smithy-run-1");
        when(agent.send(anyString())).thenReturn("looked at it");

        takeover = new RunTakeover(store, environments, new RunLocks(), agentConfig());
    }

    private String sentPrompt() {
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(agent).send(prompt.capture());
        return prompt.getValue();
    }

    @Test
    void writesTheImageIntoTheContainerAndNamesItInThePrompt() {
        var png = new byte[] { 1, 2, 3 };

        assertEquals(
            "looked at it",
            takeover.send(RUN, "This looks wrong", List.of(new Screenshot("login-error.png", png)), List.of())
        );

        var filename = ArgumentCaptor.forClass(String.class);
        verify(container).copyToContainer(eq(DIR), eq(png), filename.capture());
        assertTrue(filename.getValue().endsWith("-1-login-error.png"), filename.getValue());

        String prompt = sentPrompt();
        assertTrue(prompt.startsWith("This looks wrong"), prompt);
        assertTrue(prompt.contains(DIR + "/" + filename.getValue()), prompt);
        assertTrue(prompt.contains("Read them before you reply"), prompt);
    }

    /** Two pastes in one message are two files, not one overwriting the other. */
    @Test
    void keepsSeveralImagesApartEvenWhenTheyShareAName() {
        takeover.send(
            RUN,
            "Before and after",
            List.of(new Screenshot("image.png", new byte[] { 1 }), new Screenshot("image.png", new byte[] { 2 })),
            List.of()
        );

        var filenames = ArgumentCaptor.forClass(String.class);
        verify(container, times(2)).copyToContainer(eq(DIR), any(), filenames.capture());
        var written = filenames.getAllValues();
        assertNotEquals(written.get(0), written.get(1));
        assertTrue(written.get(0).endsWith("-1-image.png"), written.get(0));
        assertTrue(written.get(1).endsWith("-2-image.png"), written.get(1));
    }

    /** A screenshot on its own is a message; it should not reach the agent bare. */
    @Test
    void givesAnImageOnlyMessageSomethingToActOn() {
        takeover.send(RUN, "", List.of(new Screenshot("shot.png", new byte[] { 1 })), List.of());

        assertTrue(sentPrompt().startsWith("Look at the attached screenshot(s)."), sentPrompt());
    }

    @Test
    void leavesAPlainMessageExactlyAsItWasTyped() {
        takeover.send(RUN, "just words", List.of());

        assertEquals("just words", sentPrompt());
        verify(container, never()).ensureScratchDir(anyString());
        verify(container, never()).copyToContainer(any(), any(), any());
    }

    /** A name from a browser is not to be trusted straight into a shell path. */
    @Test
    void stripsPathsAndOddCharactersOutOfAnUploadedName() {
        takeover.send(RUN, "look", List.of(new Screenshot("../../etc/pa ss;wd.png", new byte[] { 1 })), List.of());

        var filename = ArgumentCaptor.forClass(String.class);
        verify(container).copyToContainer(eq(DIR), any(), filename.capture());
        assertTrue(filename.getValue().endsWith("-1-pa-ss-wd.png"), filename.getValue());
        assertFalse(filename.getValue().contains("/"), filename.getValue());
    }

    @Test
    void answersTheMessageEvenWhenAnImageCannotBeWritten() {
        doThrow(new RuntimeException("no space")).when(container).copyToContainer(any(), any(), any());

        assertEquals(
            "looked at it",
            takeover.send(RUN, "look", List.of(new Screenshot("a.png", new byte[] { 1 })), List.of())
        );
        // Nothing landed, so nothing is promised to the agent.
        assertEquals("look", sentPrompt());
    }

    @Test
    void recordsHowManyImagesTheTurnCarried() {
        takeover.send(RUN, "look", List.of(new Screenshot("a.png", new byte[] { 1 })), List.of());

        verify(store).appendEvent(eq("run-1"), eq("takeover.message"), eq(Map.of("length", 4, "screenshots", 1)));
    }

    private static ClaudeAgentConfig agentConfig() {
        return new ClaudeAgentConfig("claude-opus-5", null, null, null, "5m");
    }
}
