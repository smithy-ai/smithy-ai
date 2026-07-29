package dev.smithyai.orchestrator.workflow.flows.smithy;

import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.ForemanConfig;
import dev.smithyai.orchestrator.config.KnowledgebaseConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.workflow.flows.foreman.ForemanWorkflowFactory;
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
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SmithyWorkflowFactory extends AbstractWorkflowFactory<SmithyWorkflowInstance> {

    public static final List<String> REFINE_TOOLS = List.of("Read", "Write", "Glob", "Grep", "Bash");
    public static final List<String> BUILD_TOOLS = List.of("Read", "Edit", "Write", "Bash");

    private final ContainerService containerService;
    private final DockerConfig dockerConfig;
    private final VcsProviderConfig vcsConfig;
    private final KnowledgebaseConfig knowledgebaseConfig;
    private final BotConfig botConfig;
    private final ForemanConfig foremanConfig;
    private final PromptRenderer renderer;
    private final VcsClient vcsClient;
    private final IssueTrackerClient issueTracker;

    public SmithyWorkflowFactory(
        DockerConfig dockerConfig,
        VcsProviderConfig vcsConfig,
        KnowledgebaseConfig knowledgebaseConfig,
        BotConfig botConfig,
        ForemanConfig foremanConfig,
        ContainerService containerService,
        PromptRenderer renderer,
        @Qualifier("smithyVcs") VcsClient vcsClient,
        @Qualifier("smithyIssueTracker") IssueTrackerClient issueTracker
    ) {
        this.dockerConfig = dockerConfig;
        this.vcsConfig = vcsConfig;
        this.knowledgebaseConfig = knowledgebaseConfig;
        this.botConfig = botConfig;
        this.foremanConfig = foremanConfig;
        this.containerService = containerService;
        this.renderer = renderer;
        this.vcsClient = vcsClient;
        // In foreman mode smithy's issues are child issues on the VCS itself
        // (numeric refs), not stories on the external tracker — so talk to the
        // VCS about them even when the issue provider is e.g. Jira.
        this.issueTracker =
            foremanConfig.enabled() && vcsClient instanceof IssueTrackerClient vcsTracker ? vcsTracker : issueTracker;
    }

    @Override
    public EventAction decideEventAction(WorkflowEvent event) {
        // Tracker-key stories (e.g. ECD-4309) belong to the foreman when enabled
        if (
            foremanConfig.enabled() &&
            event instanceof WorkflowEvent.IssueScoped scoped &&
            ForemanWorkflowFactory.isTrackerKey(scoped.ctx().issueRef())
        ) {
            return EventAction.IGNORE;
        }

        var key = containerKey(event);
        if (key == null) return EventAction.IGNORE;

        return switch (event) {
            case WorkflowEvent.IssueAssigned _ -> new EventAction.Create(key);
            case WorkflowEvent.IssueUnassigned _, WorkflowEvent.PrUnassigned _ -> new EventAction.Destroy(key);
            case
                WorkflowEvent.IssueComment _,
                WorkflowEvent.PlanApproved _,
                WorkflowEvent.HumanPush _,
                WorkflowEvent.PrConversationComment _,
                WorkflowEvent.PrReviewComment _,
                WorkflowEvent.ReviewSubmitted _,
                WorkflowEvent.PrFinalized _,
                WorkflowEvent.CiFailure _,
                WorkflowEvent.CiRecovery _ -> new EventAction.Dispatch(key);
            default -> EventAction.IGNORE;
        };
    }

    @Override
    protected SmithyWorkflowInstance createInstance(String key, WorkflowEvent event) {
        var session = containerService.createSession(key);
        return new SmithyWorkflowInstance(
            session,
            vcsClient,
            issueTracker,
            renderer,
            dockerConfig,
            vcsConfig,
            knowledgebaseConfig,
            botConfig,
            augmentTools(REFINE_TOOLS),
            () -> removeInstance(key)
        );
    }

    @Override
    protected SmithyWorkflowInstance resurrectInstance(String key, WorkflowEvent event) {
        boolean prComment =
            event instanceof WorkflowEvent.PrConversationComment ||
            event instanceof WorkflowEvent.PrReviewComment ||
            event instanceof WorkflowEvent.ReviewSubmitted;
        if (!prComment) return null;

        log.info("Resurrecting {} in build stage for {}", key, event.getClass().getSimpleName());
        var session = containerService.createSession(key);
        return new SmithyWorkflowInstance(
            session,
            vcsClient,
            issueTracker,
            renderer,
            dockerConfig,
            vcsConfig,
            knowledgebaseConfig,
            botConfig,
            augmentTools(BUILD_TOOLS),
            () -> removeInstance(key),
            Stage.BUILD,
            null
        );
    }

    @Override
    public boolean canRecover(String containerName, ContainerState state) {
        return (
            containerName.startsWith("smithy.") &&
            state.workflowType() == WorkflowType.SMITHY &&
            !Stage.DONE.value().equals(state.stage())
        );
    }

    @Override
    public SmithyWorkflowInstance recoverInstance(String containerName, ContainerState state) {
        Stage stage = Stage.fromValue(state.stage());
        List<String> tools = stage == Stage.BUILD ? BUILD_TOOLS : REFINE_TOOLS;
        var session = containerService.createSession(containerName, state);
        return new SmithyWorkflowInstance(
            session,
            vcsClient,
            issueTracker,
            renderer,
            dockerConfig,
            vcsConfig,
            knowledgebaseConfig,
            botConfig,
            augmentTools(tools),
            () -> removeInstance(containerName),
            stage,
            state.sessionId()
        );
    }

    private List<String> augmentTools(List<String> baseTools) {
        if (knowledgebaseConfig == null || !knowledgebaseConfig.isActive()) {
            log.debug("Knowledgebase not active, skipping tool augmentation");
            return baseTools;
        }
        var tools = new java.util.ArrayList<>(baseTools);
        tools.add(knowledgebaseConfig.mcpToolAllowName());
        log.info("Augmented tools with knowledgebase: {}", knowledgebaseConfig.mcpToolAllowName());
        return List.copyOf(tools);
    }

    private static String containerKey(WorkflowEvent event) {
        var info = event.info();
        String issueRef = switch (event) {
            case WorkflowEvent.IssueScoped e -> e.ctx().issueRef();
            case WorkflowEvent.PrScoped e -> Naming.parseIssueRefFromBranch(e.prc().headBranch());
            case WorkflowEvent.HumanPush e -> Naming.parseIssueRefFromBranch(e.branch());
            case WorkflowEvent.CiFailure e -> Naming.parseIssueRefFromBranch(e.ciRun().headBranch());
            case WorkflowEvent.CiRecovery e -> Naming.parseIssueRefFromBranch(e.ciRun().headBranch());
            default -> null;
        };
        return issueRef != null ? Naming.containerName("smithy", info.owner(), info.repo(), issueRef) : null;
    }
}
