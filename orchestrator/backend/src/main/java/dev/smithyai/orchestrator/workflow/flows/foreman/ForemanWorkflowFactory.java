package dev.smithyai.orchestrator.workflow.flows.foreman;

import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.ForemanConfig;
import dev.smithyai.orchestrator.config.ReposManifest;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.service.claude.PromptRenderer;
import dev.smithyai.orchestrator.service.docker.ContainerService;
import dev.smithyai.orchestrator.service.docker.dto.ContainerState;
import dev.smithyai.orchestrator.service.docker.dto.WorkflowType;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.workflow.EventAction;
import dev.smithyai.orchestrator.workflow.shared.AbstractWorkflowFactory;
import dev.smithyai.orchestrator.workflow.shared.utils.Naming;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Routes issue-tracker stories (refs that are tracker keys, e.g. "ECD-4309")
 * to the feature-level foreman agent. Numeric refs — plain VCS issues — are
 * never handled here; those stay with the per-issue smithy workflow.
 */
@Slf4j
@Component
public class ForemanWorkflowFactory extends AbstractWorkflowFactory<ForemanWorkflowInstance> {

    private final ForemanConfig foremanConfig;
    private final ReposManifest manifest;
    private final ContainerService containerService;
    private final DockerConfig dockerConfig;
    private final VcsProviderConfig vcsConfig;
    private final BotConfig botConfig;
    private final PromptRenderer renderer;
    private final VcsClient vcsClient;
    private final IssueTrackerClient storyTracker;
    private final IssueTrackerClient childIssueTracker;

    public ForemanWorkflowFactory(
        ForemanConfig foremanConfig,
        DockerConfig dockerConfig,
        VcsProviderConfig vcsConfig,
        BotConfig botConfig,
        ContainerService containerService,
        PromptRenderer renderer,
        @Qualifier("smithyVcs") VcsClient vcsClient,
        @Qualifier("smithyIssueTracker") IssueTrackerClient storyTracker
    ) {
        this.foremanConfig = foremanConfig;
        this.dockerConfig = dockerConfig;
        this.vcsConfig = vcsConfig;
        this.botConfig = botConfig;
        this.containerService = containerService;
        this.renderer = renderer;
        this.vcsClient = vcsClient;
        this.storyTracker = storyTracker;
        this.manifest = foremanConfig.enabled() ? ReposManifest.load(Path.of(foremanConfig.manifestPath())) : null;
        if (!(vcsClient instanceof IssueTrackerClient vcsTracker)) {
            throw new IllegalStateException("Foreman requires a VCS client that can manage issues");
        }
        this.childIssueTracker = vcsTracker;
        if (foremanConfig.enabled()) {
            log.info("Foreman enabled with {} manifest repos", manifest.repos().size());
        }
    }

    /**
     * Which foreman owns each child issue, keyed "owner/repo#issueRef".
     * Populated on fan-out and rebuilt from container state on recovery.
     */
    private final ConcurrentHashMap<String, String> childIndex = new ConcurrentHashMap<>();

    /** A tracker key like ECD-4309, as opposed to a numeric VCS issue ref. */
    public static boolean isTrackerKey(String issueRef) {
        return issueRef != null && !issueRef.chars().allMatch(Character::isDigit);
    }

    @Override
    public EventAction decideEventAction(WorkflowEvent event) {
        if (!foremanConfig.enabled()) return EventAction.IGNORE;

        // Child observation: bot activity and merges on issues/branches the
        // foreman created route to the owning feature instance.
        switch (event) {
            case WorkflowEvent.BotPlanPosted e -> {
                return dispatchForChild(e.ctx().info().owner(), e.ctx().info().repo(), e.ctx().issueRef());
            }
            case WorkflowEvent.BotPush e -> {
                String ref = Naming.parseIssueRefFromBranch(e.branch());
                if (ref == null) return EventAction.IGNORE;
                return dispatchForChild(e.info().owner(), e.info().repo(), ref);
            }
            case WorkflowEvent.PrMerged e -> {
                String ref = Naming.parseIssueRefFromBranch(e.prc().headBranch());
                if (ref == null) return EventAction.IGNORE;
                return dispatchForChild(e.prc().info().owner(), e.prc().info().repo(), ref);
            }
            default -> {}
        }

        if (!(event instanceof WorkflowEvent.IssueScoped scoped)) return EventAction.IGNORE;
        String ref = scoped.ctx().issueRef();
        if (!isTrackerKey(ref)) return EventAction.IGNORE;

        String key = containerKey(scoped);
        return switch (event) {
            case WorkflowEvent.IssueAssigned _ -> new EventAction.Create(key);
            case WorkflowEvent.IssueUnassigned _ -> new EventAction.Destroy(key);
            case WorkflowEvent.IssueComment _, WorkflowEvent.PlanApproved _ -> new EventAction.Dispatch(key);
            default -> EventAction.IGNORE;
        };
    }

    private EventAction dispatchForChild(String owner, String repo, String issueRef) {
        String foremanKey = childIndex.get(childKey(owner + "/" + repo, issueRef));
        return foremanKey != null ? new EventAction.Dispatch(foremanKey) : EventAction.IGNORE;
    }

    private static String childKey(String project, String issueRef) {
        return project + "#" + issueRef;
    }

    @Override
    protected ForemanWorkflowInstance createInstance(String key, WorkflowEvent event) {
        return newInstance(key, ForemanStage.NEW, null);
    }

    @Override
    public boolean canRecover(String containerName, ContainerState state) {
        return (
            foremanConfig.enabled() &&
            containerName.startsWith("foreman.") &&
            state.workflowType() == WorkflowType.FOREMAN &&
            !ForemanStage.DONE.value().equals(state.stage())
        );
    }

    @Override
    public ForemanWorkflowInstance recoverInstance(String containerName, ContainerState state) {
        ForemanStage stage;
        try {
            stage = ForemanStage.fromValue(state.stage());
        } catch (IllegalArgumentException e) {
            stage = ForemanStage.AWAITING_APPROVAL;
        }
        var instance = newInstance(containerName, stage, state.sessionId());
        if (stage == ForemanStage.EXECUTING) {
            // Rebuild the child routing index from the container's persisted state
            var childrenState = instance.loadChildren();
            if (childrenState != null) {
                for (var child : childrenState.children()) {
                    childIndex.put(childKey(child.project(), child.issueRef()), containerName);
                }
                log.info("Foreman {} recovered with {} children", containerName, childrenState.children().size());
            }
        }
        return instance;
    }

    private ForemanWorkflowInstance newInstance(String key, ForemanStage stage, String sessionId) {
        var session = containerService.createSession(key);
        return new ForemanWorkflowInstance(
            session,
            vcsClient,
            storyTracker,
            childIssueTracker,
            renderer,
            dockerConfig,
            vcsConfig,
            foremanConfig,
            manifest,
            botConfig,
            (project, issueRef) -> childIndex.put(childKey(project, issueRef), key),
            () -> {
                removeInstance(key);
                childIndex.values().removeIf(key::equals);
            },
            stage,
            sessionId
        );
    }

    private static String containerKey(WorkflowEvent.IssueScoped event) {
        var info = event.ctx().info();
        return Naming.containerName("foreman", info.owner(), info.repo(), event.ctx().issueRef());
    }
}
