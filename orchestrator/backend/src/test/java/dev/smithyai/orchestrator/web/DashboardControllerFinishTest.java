package dev.smithyai.orchestrator.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.smithyai.orchestrator.runtime.engine.RunEngine;
import dev.smithyai.orchestrator.runtime.engine.RunTakeover;
import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.runtime.store.Run;
import dev.smithyai.orchestrator.runtime.store.RunRecorder;
import dev.smithyai.orchestrator.runtime.store.RunStatus;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import dev.smithyai.orchestrator.service.docker.ContainerService;
import dev.smithyai.orchestrator.service.metrics.MetricsRecorder;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DashboardControllerFinishTest {

    private static final Run RUN = new Run(
        "run-1",
        "gm-feature",
        "2",
        RunStatus.WAITING,
        "awaiting_approval",
        Map.of(),
        null,
        null,
        Instant.now(),
        Instant.now(),
        null
    );

    private RunStore store;
    private RunTakeover takeover;
    private RunEngine engine;
    private DashboardController controller;

    @BeforeEach
    void setUp() {
        store = mock(RunStore.class);
        takeover = mock(RunTakeover.class);
        engine = mock(RunEngine.class);
        controller = new DashboardController(
            mock(ContainerService.class),
            mock(MetricsRecorder.class),
            store,
            mock(RunEnvironments.class),
            takeover,
            engine
        );
    }

    @Test
    void anUnknownContainerIsNotFound() {
        when(store.findByEnvironment(RunRecorder.CONTAINER, "nope")).thenReturn(Optional.empty());

        assertEquals(404, controller.finishTakeover("nope").getStatusCode().value());
        verify(takeover, never()).finish(any());
    }

    @Test
    void handingBackARunNobodyHoldsIsAConflict() {
        when(store.findByEnvironment(RunRecorder.CONTAINER, "box")).thenReturn(Optional.of(RUN));
        when(takeover.isHeld("run-1")).thenReturn(false);

        assertEquals(409, controller.finishTakeover("box").getStatusCode().value());
        verify(takeover, never()).finish(any());
    }

    @Test
    void handingBackIsAcceptedAtOnceAndHandedToTheTakeover() {
        when(store.findByEnvironment(RunRecorder.CONTAINER, "box")).thenReturn(Optional.of(RUN));
        when(takeover.isHeld("run-1")).thenReturn(true);

        var response = controller.finishTakeover("box");

        assertEquals(202, response.getStatusCode().value());
        verify(takeover).finish(RUN);
    }

    @Test
    void theStatusSaysWhetherHandingBackMeansAnythingRightNow() {
        when(store.findByEnvironment(RunRecorder.CONTAINER, "box")).thenReturn(Optional.of(RUN));
        when(takeover.isHeld("run-1")).thenReturn(true);
        when(engine.handlesNow(RUN, "signal:" + RunTakeover.FINISHED)).thenReturn(true);

        var status = controller.takeoverStatus("box").getBody();

        assertNotNull(status);
        assertTrue(status.active());
        assertTrue(status.canFinish());
    }
}
