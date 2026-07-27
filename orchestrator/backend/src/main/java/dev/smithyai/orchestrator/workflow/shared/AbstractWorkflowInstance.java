package dev.smithyai.orchestrator.workflow.shared;

import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.KnowledgebaseConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.service.claude.ClaudeSession;
import dev.smithyai.orchestrator.service.claude.PromptRenderer;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractWorkflowInstance {

    protected final ContainerSession session;
    protected ClaudeSession claude;
    protected final VcsClient vcsClient;
    protected final IssueTrackerClient issueTracker;
    protected final PromptRenderer renderer;
    protected final DockerConfig dockerConfig;
    protected final VcsProviderConfig vcsConfig;
    protected final KnowledgebaseConfig knowledgebaseConfig;
    private final Runnable destroyCallback;
    private final ExecutorService eventThread;

    private static final Duration TAKEOVER_TTL = Duration.ofSeconds(30);
    private volatile Instant humanControlUntil = Instant.EPOCH;

    protected AbstractWorkflowInstance(
        ContainerSession session,
        VcsClient vcsClient,
        IssueTrackerClient issueTracker,
        PromptRenderer renderer,
        DockerConfig dockerConfig,
        VcsProviderConfig vcsConfig,
        KnowledgebaseConfig knowledgebaseConfig,
        List<String> tools,
        Runnable destroyCallback
    ) {
        this(
            session,
            vcsClient,
            issueTracker,
            renderer,
            dockerConfig,
            vcsConfig,
            knowledgebaseConfig,
            tools,
            destroyCallback,
            null
        );
    }

    protected AbstractWorkflowInstance(
        ContainerSession session,
        VcsClient vcsClient,
        IssueTrackerClient issueTracker,
        PromptRenderer renderer,
        DockerConfig dockerConfig,
        VcsProviderConfig vcsConfig,
        KnowledgebaseConfig knowledgebaseConfig,
        List<String> tools,
        Runnable destroyCallback,
        String existingSessionId
    ) {
        this.session = session;
        this.claude =
            existingSessionId != null
                ? new ClaudeSession(session, tools, existingSessionId, knowledgebaseConfig)
                : new ClaudeSession(session, tools, knowledgebaseConfig);
        this.vcsClient = vcsClient;
        this.issueTracker = issueTracker;
        this.renderer = renderer;
        this.dockerConfig = dockerConfig;
        this.vcsConfig = vcsConfig;
        this.knowledgebaseConfig = knowledgebaseConfig;
        this.destroyCallback = destroyCallback;
        this.eventThread = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("wf-" + session.getContainerName() + "-", 0).factory()
        );
    }

    public String containerName() {
        return session.getContainerName();
    }

    public ContainerSession session() {
        return session;
    }

    public boolean exists() {
        return session.exists();
    }

    public void onEvent(WorkflowEvent event) {
        eventThread.submit(() -> {
            if (isHumanControlled()) {
                log.warn(
                    "Dropping event {} on {} — session is under human control",
                    event.getClass().getSimpleName(),
                    session.getContainerName()
                );
                return;
            }
            try {
                handleEvent(event);
            } catch (Exception e) {
                log.error("Event {} failed in {}", event.getClass().getSimpleName(), session.getContainerName(), e);
            }
        });
    }

    protected abstract void handleEvent(WorkflowEvent event);

    // ── Human takeover ───────────────────────────────────────

    /**
     * Whether a human currently controls this instance's Claude session.
     * Control is a heartbeat lease: it expires automatically when the
     * dashboard stops renewing it, at which point the agent resumes
     * handling workflow events.
     */
    public boolean isHumanControlled() {
        return Instant.now().isBefore(humanControlUntil);
    }

    /**
     * Acquire or renew human control. Returns the lease expiry.
     */
    public Instant takeoverHeartbeat() {
        boolean acquired = !isHumanControlled();
        Instant until = Instant.now().plus(TAKEOVER_TTL);
        humanControlUntil = until;
        if (acquired) {
            log.info("Human took over session on {}", session.getContainerName());
        }
        return until;
    }

    public void releaseTakeover() {
        if (isHumanControlled()) {
            log.info("Human takeover released on {}", session.getContainerName());
        }
        humanControlUntil = Instant.EPOCH;
    }

    /**
     * Send a human-authored message into the agent's Claude session and
     * return the reply. Runs on the instance's event thread, so it is
     * serialized with (and queues behind) any in-flight agent work.
     */
    public String sendHumanMessage(String text) {
        var future = eventThread.submit(() -> {
            String reply = claude.send(text);
            syncSessionId();
            return reply;
        });
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Human message failed on " + session.getContainerName(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending human message", e);
        }
    }

    public void destroy() {
        destroyCallback.run();
        eventThread.shutdown();
        try {
            if (!eventThread.awaitTermination(30, TimeUnit.SECONDS)) {
                eventThread.shutdownNow();
            }
        } catch (InterruptedException e) {
            eventThread.shutdownNow();
            Thread.currentThread().interrupt();
        }
        session.destroy();
    }

    protected void syncSessionId() {
        session.updateState(s -> s.withSessionId(claude.getSessionId()));
    }
}
