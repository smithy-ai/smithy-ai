package dev.smithyai.orchestrator.workflow.flows.architect;

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
public class ArchitectReviewFactory extends AbstractWorkflowFactory<ArchitectReviewInstance> {

    public static final List<String> TOOLS = List.of("Read", "Glob", "Grep", "Bash");

    private final ContainerService containerService;
    private final DockerConfig dockerConfig;
    private final VcsProviderConfig vcsConfig;
    private final PromptRenderer renderer;
    private final VcsClient vcsClient;
    private final IssueTrackerClient issueTracker;

    public ArchitectReviewFactory(
        DockerConfig dockerConfig,
        VcsProviderConfig vcsConfig,
        ContainerService containerService,
        PromptRenderer renderer,
        @Qualifier("architectVcs") VcsClient vcsClient,
        @Qualifier("architectIssueTracker") IssueTrackerClient issueTracker
    ) {
        this.dockerConfig = dockerConfig;
        this.vcsConfig = vcsConfig;
        this.containerService = containerService;
        this.renderer = renderer;
        this.vcsClient = vcsClient;
        this.issueTracker = issueTracker;
    }

    @Override
    public EventAction decideEventAction(WorkflowEvent event) {
        return switch (event) {
            case WorkflowEvent.ReviewRequested e -> {
                var prc = e.prc();
                String contextRepo = Naming.contextRepoName(prc.info().repo());
                if (!vcsClient.repoExists(prc.info().owner(), contextRepo)) {
                    log.warn("Context repo {}/{} does not exist, skipping review", prc.info().owner(), contextRepo);
                    yield EventAction.IGNORE;
                }
                String key = architectContainerName(prc.info().owner(), prc.info().repo(), "pr-" + prc.number());
                yield new EventAction.Create(key);
            }
            case WorkflowEvent.PrConversationComment e -> {
                var resolved = resolveCommentKey(e, "pr-");
                if (resolved == null) yield EventAction.IGNORE;
                var instance = instances.get(resolved);
                yield (instance != null && instance.exists()) ? new EventAction.Dispatch(resolved) : EventAction.IGNORE;
            }
            default -> EventAction.IGNORE;
        };
    }

    @Override
    protected ArchitectReviewInstance createInstance(String key, WorkflowEvent event) {
        var session = containerService.createSession(key);
        return new ArchitectReviewInstance(
            session,
            vcsClient,
            issueTracker,
            renderer,
            dockerConfig,
            vcsConfig,
            TOOLS,
            () -> removeInstance(key)
        );
    }

    @Override
    public boolean canRecover(String containerName, ContainerState state) {
        return (
            containerName.startsWith("architect.") &&
            containerName.contains(".pr-") &&
            state.workflowType() == WorkflowType.ARCHITECT &&
            !ReviewStage.DONE.value().equals(state.stage())
        );
    }

    @Override
    public ArchitectReviewInstance recoverInstance(String containerName, ContainerState state) {
        ReviewStage stage = ReviewStage.fromValue(state.stage());
        var session = containerService.createSession(containerName);
        return new ArchitectReviewInstance(
            session,
            vcsClient,
            issueTracker,
            renderer,
            dockerConfig,
            vcsConfig,
            TOOLS,
            () -> removeInstance(containerName),
            stage,
            state.sessionId()
        );
    }

    static String resolveCommentKey(WorkflowEvent.PrConversationComment e, String prefix) {
        var info = e.prc().info();
        String lookupRepo = info.repo();
        int lookupPr = e.prc().number();
        if (info.repo().endsWith("-context")) {
            lookupRepo = info.repo().substring(0, info.repo().length() - 8);
            Integer sourcePrId = Naming.parseIssueIdFromBranch(e.prc().headBranch());
            if (sourcePrId != null) {
                lookupPr = sourcePrId;
            }
        }
        return architectContainerName(info.owner(), lookupRepo, prefix + lookupPr);
    }

    static String architectContainerName(String owner, String repo, String identifier) {
        return "architect." + owner + "." + repo + "." + identifier;
    }
}
