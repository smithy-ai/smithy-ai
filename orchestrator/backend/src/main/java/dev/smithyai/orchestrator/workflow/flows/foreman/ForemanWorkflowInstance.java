package dev.smithyai.orchestrator.workflow.flows.foreman;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.ForemanConfig;
import dev.smithyai.orchestrator.config.ReposManifest;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.service.claude.PromptRenderer;
import dev.smithyai.orchestrator.service.claude.dto.FeaturePlan;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import dev.smithyai.orchestrator.service.docker.dto.ContainerConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerState;
import dev.smithyai.orchestrator.service.docker.dto.WorkflowType;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.workflow.shared.AbstractWorkflowInstance;
import dev.smithyai.orchestrator.workflow.shared.StateMachine;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Feature-level agent: turns an issue-tracker story into a reviewed cross-repo
 * plan, and on human approval creates the per-repo child issues that the
 * per-issue smithy workflow then picks up. The agent thinks; every outward
 * action (story comments, issue creation) is executed here, Java-side.
 */
@Slf4j
public class ForemanWorkflowInstance extends AbstractWorkflowInstance {

    public static final List<String> TOOLS = List.of("Read", "Glob", "Grep", "Bash");

    private static final String PLAN_PATH = "/tmp/foreman-plan.json";
    private static final String CHILDREN_PATH = "/tmp/foreman-children.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A created child issue and the metadata needed for wave progression. */
    public record Child(String project, String issueRef, List<Integer> dependsOn, boolean assigned, boolean merged) {}

    private final ForemanConfig foremanConfig;
    private final ReposManifest manifest;
    private final IssueTrackerClient storyTracker;
    private final IssueTrackerClient childIssueTracker;
    private final String smithyBotUser;
    private final StateMachine<ForemanStage> stateMachine;

    public ForemanWorkflowInstance(
        ContainerSession session,
        VcsClient vcsClient,
        IssueTrackerClient storyTracker,
        IssueTrackerClient childIssueTracker,
        PromptRenderer renderer,
        DockerConfig dockerConfig,
        VcsProviderConfig vcsConfig,
        ForemanConfig foremanConfig,
        ReposManifest manifest,
        BotConfig botConfig,
        Runnable destroyCallback,
        ForemanStage initialStage,
        String existingSessionId
    ) {
        super(
            session,
            vcsClient,
            storyTracker,
            renderer,
            dockerConfig,
            vcsConfig,
            null,
            TOOLS,
            destroyCallback,
            existingSessionId
        );
        this.foremanConfig = foremanConfig;
        this.manifest = manifest;
        this.storyTracker = storyTracker;
        this.childIssueTracker = childIssueTracker;
        this.smithyBotUser = botConfig.resolvedSmithyUser();
        // @formatter:off
        this.stateMachine = StateMachine.builder(ForemanStage.class, initialStage)
            .in(ForemanStage.NEW)
                .on(WorkflowEvent.IssueAssigned.class, this::handleStoryAssigned).then(ForemanStage.AWAITING_APPROVAL)
                .done()
            .in(ForemanStage.AWAITING_APPROVAL)
                .on(WorkflowEvent.IssueComment.class, this::handleStoryComment).thenRemain()
                .on(WorkflowEvent.PlanApproved.class, this::handlePlanApproved).then(ForemanStage.EXECUTING)
                .done()
            .in(ForemanStage.EXECUTING)
                .on(WorkflowEvent.IssueComment.class, this::handleStoryComment).thenRemain()
                .done()
            .in(ForemanStage.DONE)
                .done()
            .build();
        // @formatter:on
    }

    @Override
    protected void handleEvent(WorkflowEvent event) {
        if (!stateMachine.canFire(event.getClass())) {
            log.debug("Foreman ignoring {} in stage {}", event.getClass().getSimpleName(), stateMachine.state());
            return;
        }
        stateMachine.fire(event);
    }

    // ── PLANNING ─────────────────────────────────────────────

    private void handleStoryAssigned(WorkflowEvent.IssueAssigned e) {
        var ctx = e.ctx();
        try {
            if (session.exists()) {
                log.debug("Foreman container {} already exists, skipping", session.getContainerName());
                return;
            }

            log.info("Foreman planning story {} in {}", ctx.issueRef(), session.getContainerName());

            // Clone every manifest repo: the first is the workspace, the rest
            // land under repos/<name>. Shallow blobless clones keep this cheap.
            var repos = manifest.repos();
            var first = repos.getFirst();
            var extra = new ArrayList<ContainerConfig.ExtraRepo>();
            for (var r : repos.subList(1, repos.size())) {
                extra.add(new ContainerConfig.ExtraRepo(cloneUrlFor(r.project()), "repos/" + repoName(r.project()), ""));
            }
            var containerConfig = ContainerConfig.builder()
                .cloneUrl(cloneUrlFor(first.project()))
                .branch("")
                .sourceBranch("")
                .cacheVolumes(dockerConfig.getCacheVolumeMap())
                .workflowType(WorkflowType.FOREMAN)
                .extraRepos(extra)
                .build();
            session.initContainer(containerConfig, ForemanStage.AWAITING_APPROVAL.value());

            FeaturePlan plan = draftPlan(ctx.issueRef(), ctx.title(), ctx.body(), null);
            storePlan(plan);
            storyTracker.createIssueComment(ctx.info().owner(), ctx.info().repo(), ctx.issueRef(), renderPlanComment(plan));
            log.info("Foreman posted plan for {} ({} issues)", ctx.issueRef(), plan.issues().size());
        } catch (Exception ex) {
            log.error("Foreman planning failed for {}", ctx.issueRef(), ex);
        }
    }

    private void handleStoryComment(WorkflowEvent.IssueComment e) {
        var ctx = e.ctx();
        try {
            if (!session.exists()) return;
            session.updateState(ContainerState::touch);

            if (stateMachine.state() == ForemanStage.EXECUTING) {
                // Post-approval comments are questions/steering, not plan edits
                String reply = claude.send(
                    renderer.render(
                        "foreman_status.md.j2",
                        Map.of("story_ref", ctx.issueRef(), "comment_body", e.commentBody())
                    )
                );
                syncSessionId();
                storyTracker.createIssueComment(ctx.info().owner(), ctx.info().repo(), ctx.issueRef(), reply);
                return;
            }

            FeaturePlan plan = draftPlan(ctx.issueRef(), ctx.title(), ctx.body(), e.commentBody());
            storePlan(plan);
            storyTracker.createIssueComment(ctx.info().owner(), ctx.info().repo(), ctx.issueRef(), renderPlanComment(plan));
            log.info("Foreman revised plan for {}", ctx.issueRef());
        } catch (Exception ex) {
            log.error("Foreman plan revision failed for {}", ctx.issueRef(), ex);
        }
    }

    // ── EXECUTING ────────────────────────────────────────────

    private void handlePlanApproved(WorkflowEvent.PlanApproved e) {
        var ctx = e.ctx();
        try {
            FeaturePlan plan = loadPlan();
            if (plan == null || plan.issues().isEmpty()) {
                storyTracker.createIssueComment(
                    ctx.info().owner(),
                    ctx.info().repo(),
                    ctx.issueRef(),
                    "Approval received but I have no stored plan — comment on the story to have me re-plan."
                );
                return;
            }

            session.updateState(s -> s.withStage(ForemanStage.EXECUTING.value()).touch());

            var children = new ArrayList<Child>();
            int cap = foremanConfig.resolvedMaxIssues();
            for (int i = 0; i < plan.issues().size(); i++) {
                var planned = plan.issues().get(i);
                if (i >= cap) {
                    log.warn("Foreman issue cap ({}) reached for {}, skipping remaining issues", cap, ctx.issueRef());
                    break;
                }
                if (!manifest.containsProject(planned.project())) {
                    log.warn("Foreman plan targets {} which is not in the manifest, skipping", planned.project());
                    continue;
                }
                String[] parts = planned.project().split("/", 2);
                String bodyWithStory = planned.body() + "\n\n---\nParent story: " + ctx.issueRef();
                var issue = childIssueTracker.createIssue(parts[0], parts[1], planned.title(), bodyWithStory, List.of());
                children.add(new Child(planned.project(), issue.issueRef(), planned.dependsOn(), false, false));
                log.info("Foreman created child issue {}#{} for {}", planned.project(), issue.issueRef(), ctx.issueRef());
            }

            // Assign wave 1: issues with no dependencies
            var assigned = new ArrayList<Child>();
            for (var child : children) {
                if (child.dependsOn().isEmpty()) {
                    String[] parts = child.project().split("/", 2);
                    childIssueTracker.setIssueAssignees(parts[0], parts[1], child.issueRef(), List.of(smithyBotUser));
                    assigned.add(new Child(child.project(), child.issueRef(), child.dependsOn(), true, false));
                } else {
                    assigned.add(child);
                }
            }
            storeChildren(assigned);

            var summary = new StringBuilder("Plan approved — created ").append(assigned.size()).append(" issues:\n");
            for (var c : assigned) {
                summary
                    .append("- ")
                    .append(c.project())
                    .append("#")
                    .append(c.issueRef())
                    .append(c.assigned() ? " (assigned to smithy)" : " (waiting on dependencies)")
                    .append("\n");
            }
            storyTracker.createIssueComment(ctx.info().owner(), ctx.info().repo(), ctx.issueRef(), summary.toString());
        } catch (Exception ex) {
            log.error("Foreman execution failed for {}", ctx.issueRef(), ex);
        }
    }

    // ── Plan drafting & persistence ──────────────────────────

    private FeaturePlan draftPlan(String storyRef, String title, String body, String feedback) {
        var manifestEntries = new ArrayList<Map<String, String>>();
        for (var r : manifest.repos()) {
            var entry = new HashMap<String, String>();
            entry.put("project", r.project());
            entry.put("description", r.description() != null ? r.description() : "");
            entry.put("specs", r.specs() != null ? r.specs() : "");
            manifestEntries.add(entry);
        }
        var vars = new HashMap<String, Object>();
        vars.put("story_ref", storyRef);
        vars.put("story_title", title != null ? title : "");
        vars.put("story_body", body != null ? body : "");
        vars.put("repos", manifestEntries);
        vars.put("max_issues", foremanConfig.resolvedMaxIssues());
        vars.put("feedback", feedback != null ? feedback : "");

        String template = feedback == null ? "foreman_plan.md.j2" : "foreman_plan_revise.md.j2";
        FeaturePlan plan = claude.send(renderer.render(template, vars), FeaturePlan.class);
        syncSessionId();
        return plan;
    }

    private String renderPlanComment(FeaturePlan plan) {
        var sb = new StringBuilder("## Feature plan\n\n").append(plan.summary()).append("\n");
        for (int i = 0; i < plan.issues().size(); i++) {
            var issue = plan.issues().get(i);
            sb.append("\n### ").append(i + 1).append(". ").append(issue.project()).append(" — ").append(issue.title());
            if (!issue.dependsOn().isEmpty()) {
                var deps = issue.dependsOn().stream().map(d -> String.valueOf(d + 1)).toList();
                sb.append("\n_depends on: ").append(String.join(", ", deps)).append("_");
            }
            sb.append("\n\n").append(issue.body()).append("\n");
        }
        if (!plan.openQuestions().isEmpty()) {
            sb.append("\n### Open questions\n");
            for (String q : plan.openQuestions()) sb.append("- ").append(q).append("\n");
        }
        sb.append("\nReply on this story to revise the plan; add the approval label to start execution.");
        return sb.toString();
    }

    private void storePlan(FeaturePlan plan) throws Exception {
        session.copyToContainer("/tmp", MAPPER.writeValueAsBytes(plan), "foreman-plan.json");
    }

    private FeaturePlan loadPlan() {
        try {
            var result = session.exec("cat", PLAN_PATH);
            if (result.exitCode() != 0) return null;
            return MAPPER.readValue(result.stdout().getBytes(StandardCharsets.UTF_8), FeaturePlan.class);
        } catch (Exception e) {
            log.warn("Failed to load foreman plan from {}", session.getContainerName(), e);
            return null;
        }
    }

    private void storeChildren(List<Child> children) throws Exception {
        session.copyToContainer("/tmp", MAPPER.writeValueAsBytes(children), "foreman-children.json");
    }

    public List<Child> loadChildren() {
        try {
            var result = session.exec("cat", CHILDREN_PATH);
            if (result.exitCode() != 0) return List.of();
            return List.of(MAPPER.readValue(result.stdout().getBytes(StandardCharsets.UTF_8), Child[].class));
        } catch (Exception e) {
            log.warn("Failed to load foreman children from {}", session.getContainerName(), e);
            return List.of();
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    private String cloneUrlFor(String project) {
        String[] parts = project.split("/", 2);
        return vcsClient.cloneUrl(parts[0], parts[1]);
    }

    private static String repoName(String project) {
        int slash = project.lastIndexOf('/');
        return slash >= 0 ? project.substring(slash + 1) : project;
    }
}
