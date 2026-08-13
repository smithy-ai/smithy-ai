package dev.smithyai.orchestrator.runtime.env;

import dev.smithyai.orchestrator.config.KnowledgebaseConfig;
import dev.smithyai.orchestrator.runtime.store.Run;
import dev.smithyai.orchestrator.runtime.store.RunEnvironment;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import dev.smithyai.orchestrator.service.claude.ClaudeSession;
import dev.smithyai.orchestrator.service.docker.ContainerService;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import dev.smithyai.orchestrator.service.docker.dto.ContainerConfig;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves the container a run is working in.
 *
 * <p>This is the seam that stops the container from being the database. A run
 * records which environments it holds; this looks one up, creates it on demand,
 * and hands actions a session to work through. A run that holds none — a
 * coordinator that only reads the VCS and spawns children — needs nothing here.
 *
 * <p>The agent session id stays on the container rather than in the run store,
 * because the transcript it names lives inside that container and means nothing
 * without it.
 */
@Slf4j
@Component
public class RunEnvironments {

    private final RunStore store;
    private final ContainerService containers;
    private final KnowledgebaseConfig knowledgebaseConfig;

    public RunEnvironments(RunStore store, ContainerService containers, KnowledgebaseConfig knowledgebaseConfig) {
        this.store = store;
        this.containers = containers;
        this.knowledgebaseConfig = knowledgebaseConfig;
    }

    public Optional<ContainerSession> findContainer(Run run) {
        return store.findEnvironment(run.id(), RunEnvironment.CONTAINER).map(env -> session(env.name()));
    }

    /**
     * A session on a container that is actually running.
     *
     * <p>A container can be stopped without the run ending — a machine reboot, a
     * `docker stop`, a restart policy that lost. Every step then talks to
     * something that is not there, so it is started first. The flows this
     * replaced did the same thing on every dispatch.
     */
    private ContainerSession session(String name) {
        if (!containers.ensureRunning(name)) {
            log.warn("Container {} could not be started; steps that need it will fail", name);
        }
        return containers.createSession(name);
    }

    /** The run's container, or a failure naming the run — never a silent no-op. */
    public ContainerSession container(Run run) {
        return findContainer(run).orElseThrow(() ->
            new IllegalStateException(
                "Run %s (%s) has no container; a step needs one but container.init has not run".formatted(
                    run.id(),
                    run.workflowName()
                )
            )
        );
    }

    /**
     * Create the run's container and attach it.
     *
     * <p>Attaching is what makes an inbound event addressed to a container find
     * its run, and what makes a restart re-attach rather than fork a new run.
     */
    public ContainerSession createContainer(Run run, String name, ContainerConfig config, String initialStage) {
        // A container with this name may already exist without this run knowing:
        // the store was rebuilt, or a previous run left it behind. Adopting it
        // beats failing on a name conflict, and beats cloning a second copy of a
        // repository that is already sitting there.
        if (containers.containerExists(name)) {
            var holder = store.findByEnvironment(RunEnvironment.CONTAINER, name);
            if (holder.isPresent() && !holder.get().id().equals(run.id())) {
                throw new IllegalStateException(
                    "Container %s is held by run %s; run %s cannot take it".formatted(name, holder.get().id(), run.id())
                );
            }
            containers.ensureRunning(name);
            store.attachEnvironment(run.id(), RunEnvironment.CONTAINER, name, Map.of());
            log.info("Run {} adopted the existing container {}", run.id(), name);
            return containers.createSession(name);
        }

        var session = containers.createSession(name);
        session.initContainer(config, initialStage);
        store.attachEnvironment(run.id(), RunEnvironment.CONTAINER, name, Map.of());
        log.info("Run {} took container {}", run.id(), name);
        return session;
    }

    /**
     * The agent conversation for this run, resumed if one is already in flight.
     *
     * <p>A fresh {@link ClaudeSession} would start a new conversation and lose
     * everything the agent had established, so the id recorded on the container
     * is what makes a transition after a restart continue rather than restart.
     */
    public ClaudeSession agent(Run run, List<String> tools) {
        var session = container(run);
        String existing = session.getState().sessionId();
        return existing != null
            ? new ClaudeSession(session, tools, existing, knowledgebaseConfig)
            : new ClaudeSession(session, tools, knowledgebaseConfig);
    }

    /**
     * A conversation that starts here, whatever the container remembers.
     *
     * <p>Planning opens a session rather than continuing one, and Claude refuses
     * to open a session id that already exists — which an adopted container will
     * have from whoever used it last.
     */
    public ClaudeSession newAgent(Run run, List<String> tools) {
        return new ClaudeSession(container(run), tools, knowledgebaseConfig);
    }

    /** Record the agent's session id so the next transition resumes it. */
    public void rememberAgentSession(ContainerSession session, ClaudeSession agent) {
        session.updateState(state -> state.withSessionId(agent.getSessionId()));
    }

    public void destroyContainer(Run run) {
        findContainer(run).ifPresent(session -> {
            session.destroy();
            store.detachEnvironment(RunEnvironment.CONTAINER, session.getContainerName());
            log.info("Run {} released container {}", run.id(), session.getContainerName());
        });
    }
}
