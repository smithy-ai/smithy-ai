package dev.smithyai.orchestrator.workflow.flows.smithy;

import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.DockerConfig;
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
    private final BotConfig botConfig;
    private final PromptRenderer renderer;
    private final VcsClient vcsClient;
    private final IssueTrackerClient issueTracker;

    public SmithyWorkflowFactory(
        DockerConfig dockerConfig,
        VcsProviderConfig vcsConfig,
        BotConfig botConfig,
        ContainerService containerService,
        PromptRenderer renderer,
        @Qualifier("smithyVcs") VcsClient vcsClient,
        @Qualifier("smithyIssueTracker") IssueTrackerClient issueTracker
    ) {
        this.dockerConfig = dockerConfig;
        this.vcsConfig = vcsConfig;
        this.botConfig = botConfig;
        this.containerService = containerService;
        this.renderer = renderer;
        this.vcsClient = vcsClient;
        this.issueTracker = issueTracker;
    }

    @Override
    public EventAction decideEventAction(WorkflowEvent event) {
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
            botConfig,
            REFINE_TOOLS,
            () -> removeInstance(key)
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
        var session = containerService.createSession(containerName);
        return new SmithyWorkflowInstance(
            session,
            vcsClient,
            issueTracker,
            renderer,
            dockerConfig,
            vcsConfig,
            botConfig,
            tools,
            () -> removeInstance(containerName),
            stage,
            state.sessionId()
        );
    }

    private static String containerKey(WorkflowEvent event) {
        var info = event.info();
        Integer issueId = switch (event) {
            case WorkflowEvent.IssueScoped e -> e.ctx().number();
            case WorkflowEvent.PrScoped e -> Naming.parseIssueIdFromBranch(e.prc().headBranch());
            case WorkflowEvent.HumanPush e -> Naming.parseIssueIdFromBranch(e.branch());
            case WorkflowEvent.CiFailure e -> Naming.parseIssueIdFromBranch(e.ciRun().headBranch());
            case WorkflowEvent.CiRecovery e -> Naming.parseIssueIdFromBranch(e.ciRun().headBranch());
            default -> null;
        };
        return issueId != null ? containerName(info.owner(), info.repo(), issueId) : null;
    }

    private static String containerName(String owner, String repo, int issueId) {
        return "smithy." + owner + "." + repo + "." + issueId;
    }
}
