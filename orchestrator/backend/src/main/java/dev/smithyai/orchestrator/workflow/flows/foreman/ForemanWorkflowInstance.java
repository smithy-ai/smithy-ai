package dev.smithyai.orchestrator.workflow.flows.foreman;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.ForemanConfig;
import dev.smithyai.orchestrator.config.ReposManifest;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.service.claude.PromptRenderer;
import dev.smithyai.orchestrator.model.IssueContext;
import dev.smithyai.orchestrator.service.claude.dto.ChildPlanVerdict;
import dev.smithyai.orchestrator.service.claude.dto.FeatureExtension;
import dev.smithyai.orchestrator.service.claude.dto.FeaturePlan;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import dev.smithyai.orchestrator.service.docker.dto.ContainerConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerState;
import dev.smithyai.orchestrator.service.docker.dto.WorkflowType;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.workflow.shared.AbstractWorkflowInstance;
import dev.smithyai.orchestrator.workflow.shared.StateMachine;
import dev.smithyai.orchestrator.workflow.shared.utils.AttachmentHelper;
import dev.smithyai.orchestrator.workflow.shared.utils.Naming;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Feature-level agent: turns an issue-tracker story into a reviewed cross-repo
 * plan, and on human approval creates the per-repo child issues that the
 * per-issue smithy workflow then picks up. From there it observes its children:
 * reviews each smithy plan against the feature plan and the sibling plans,
 * approves aligned plans (or escalates), and assigns the next wave of issues
 * as dependencies merge. The agent thinks; every outward action (story
 * comments, issue creation, labels) is executed here, Java-side.
 */
@Slf4j
public class ForemanWorkflowInstance extends AbstractWorkflowInstance {

    public static final List<String> TOOLS = List.of("Read", "Glob", "Grep", "Bash");
    public static final String PLAN_APPROVED_LABEL = "Plan Approved";

    private static final int MAX_REVIEW_ROUNDS = 3;
    private static final String PLAN_PATH = "/tmp/foreman-plan.json";
    private static final String CHILDREN_PATH = "/tmp/foreman-children.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A created child issue and the metadata needed for review and wave progression. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Child(
        int planIndex,
        String project,
        String issueRef,
        List<Integer> dependsOn,
        boolean assigned,
        boolean merged,
        boolean approved,
        int reviewRounds
    ) {
        public Child {
            if (dependsOn == null) dependsOn = List.of();
        }
    }

    /** Children plus the story they belong to, persisted in the container. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChildrenState(String storyOwner, String storyRepo, String storyRef, List<Child> children) {
        public ChildrenState {
            if (children == null) children = List.of();
        }
    }

    private final ForemanConfig foremanConfig;
    private final ReposManifest manifest;
    private final IssueTrackerClient storyTracker;
    private final IssueTrackerClient childIssueTracker;
    private final String smithyBotUser;
    private final BiConsumer<String, String> childRegistrar;
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
        BiConsumer<String, String> childRegistrar,
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
        this.childRegistrar = childRegistrar;
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
                .on(WorkflowEvent.BotPlanPosted.class, this::handleBotPlanPosted).thenRemain()
                .on(WorkflowEvent.BotPush.class, this::handleBotPush).thenRemain()
                .on(WorkflowEvent.PrMerged.class, this::handleChildMerged).thenRemain()
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
            // Persist the session id before the (long) planning turn so the
            // dashboard can tail the live transcript while Claude works
            syncSessionId();

            var attachments = AttachmentHelper.fetchAndInject(
                storyTracker,
                session,
                ctx.info().owner(),
                ctx.info().repo(),
                ctx.issueRef()
            );

            FeaturePlan plan = draftPlan(ctx.issueRef(), ctx.title(), ctx.body(), null, attachments);
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
                // Post-approval comments either steer (answered in place) or
                // extend the feature with additional child issues
                handleExecutingComment(e);
                return;
            }

            // Re-fetch attachments: designs are often added together with the comment
            var attachments = AttachmentHelper.fetchAndInject(
                storyTracker,
                session,
                ctx.info().owner(),
                ctx.info().repo(),
                ctx.issueRef()
            );

            FeaturePlan plan = draftPlan(ctx.issueRef(), ctx.title(), ctx.body(), e.commentBody(), attachments);
            storePlan(plan);
            storyTracker.createIssueComment(ctx.info().owner(), ctx.info().repo(), ctx.issueRef(), renderPlanComment(plan));
            log.info("Foreman revised plan for {}", ctx.issueRef());
        } catch (Exception ex) {
            log.error("Foreman plan revision failed for {}", ctx.issueRef(), ex);
        }
    }

    // ── EXECUTING: fan-out ───────────────────────────────────

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
                children.add(new Child(i, planned.project(), issue.issueRef(), planned.dependsOn(), false, false, false, 0));
                childRegistrar.accept(planned.project(), issue.issueRef());
                log.info("Foreman created child issue {}#{} for {}", planned.project(), issue.issueRef(), ctx.issueRef());
            }

            // Assign wave 1: issues with no dependencies
            var assigned = new ArrayList<Child>();
            for (var child : children) {
                if (child.dependsOn().isEmpty()) {
                    assignToSmithy(child);
                    assigned.add(withAssigned(child));
                } else {
                    assigned.add(child);
                }
            }
            storeChildren(new ChildrenState(ctx.info().owner(), ctx.info().repo(), ctx.issueRef(), assigned));

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

    // ── EXECUTING: steering & plan extension ─────────────────

    /**
     * A story comment during execution is either a question (answered in a
     * reply) or a request to extend the feature — e.g. a repo that has since
     * been added to the manifest. The agent decides via structured output;
     * new issues join the plan, the children state, and wave scheduling
     * exactly like the original fan-out. Repos added to the manifest after
     * this container was created are cloned on demand so extension plans
     * stay grounded in real code.
     */
    private void handleExecutingComment(WorkflowEvent.IssueComment e) throws Exception {
        var ctx = e.ctx();
        FeaturePlan plan = loadPlan();
        ChildrenState state = loadChildren();
        if (state == null) {
            state = new ChildrenState(ctx.info().owner(), ctx.info().repo(), ctx.issueRef(), List.of());
        }

        // Designs are often attached along with the comment that asks for them
        var attachments = AttachmentHelper.fetchAndInject(
            storyTracker,
            session,
            ctx.info().owner(),
            ctx.info().repo(),
            ctx.issueRef()
        );

        FeatureExtension ext = requestExtension(ctx.issueRef(), e.commentBody(), plan, state, attachments, false);
        if (!ext.reposNeeded().isEmpty()) {
            cloneMissingRepos(ext.reposNeeded());
            ext = requestExtension(ctx.issueRef(), e.commentBody(), plan, state, attachments, true);
        }

        if (ext.issues().isEmpty()) {
            String reply = ext.reply() != null && !ext.reply().isBlank()
                ? ext.reply()
                : "Nothing to add — the plan is unchanged.";
            storyTracker.createIssueComment(ctx.info().owner(), ctx.info().repo(), ctx.issueRef(), reply);
            return;
        }

        extendPlan(ctx, plan, state, ext);
    }

    private FeatureExtension requestExtension(
        String storyRef,
        String commentBody,
        FeaturePlan plan,
        ChildrenState state,
        List<String> attachments,
        boolean followUp
    ) {
        var cloned = clonedRepoNames();
        var repoEntries = new ArrayList<Map<String, Object>>();
        for (var r : manifest.repos()) {
            repoEntries.add(
                Map.of(
                    "project", r.project(),
                    "description", r.description() != null ? r.description() : "",
                    "cloned", cloned.contains(repoName(r.project()))
                )
            );
        }
        var childEntries = new ArrayList<Map<String, Object>>();
        for (var c : state.children()) {
            childEntries.add(
                Map.of("index", c.planIndex(), "project", c.project(), "ref", c.issueRef(), "status", childStatus(c))
            );
        }

        var vars = new HashMap<String, Object>();
        vars.put("story_ref", storyRef);
        vars.put("comment_body", commentBody);
        vars.put("plan_summary", plan != null ? renderPlanComment(plan) : "(no stored plan)");
        vars.put("children", childEntries);
        vars.put("repos", repoEntries);
        vars.put("attachments", attachments);
        vars.put("max_issues", foremanConfig.resolvedMaxIssues());
        vars.put("next_index", plan != null ? plan.issues().size() : 0);
        vars.put("follow_up", followUp);

        FeatureExtension ext = claude.send(renderer.render("foreman_extend.md.j2", vars), FeatureExtension.class);
        syncSessionId();
        return ext;
    }

    private void extendPlan(IssueContext ctx, FeaturePlan plan, ChildrenState state, FeatureExtension ext)
        throws Exception {
        var baseIssues = plan != null ? plan.issues() : List.<FeaturePlan.PlannedIssue>of();
        int base = baseIssues.size();
        int cap = foremanConfig.resolvedMaxIssues();

        // Every extension issue joins the stored plan (planIndex arithmetic
        // and dependsOn references rely on it), even ones we skip below.
        var allIssues = new ArrayList<>(baseIssues);
        var children = new ArrayList<>(state.children());
        var created = new ArrayList<Child>();
        for (int i = 0; i < ext.issues().size(); i++) {
            var planned = ext.issues().get(i);
            allIssues.add(planned);
            if (i >= cap) {
                log.warn("Foreman extension cap ({}) reached for {}, skipping remaining issues", cap, ctx.issueRef());
                continue;
            }
            if (!manifest.containsProject(planned.project())) {
                log.warn("Foreman extension targets {} which is not in the manifest, skipping", planned.project());
                continue;
            }
            String[] parts = planned.project().split("/", 2);
            String bodyWithStory = planned.body() + "\n\n---\nParent story: " + ctx.issueRef();
            var issue = childIssueTracker.createIssue(parts[0], parts[1], planned.title(), bodyWithStory, List.of());
            var child = new Child(base + i, planned.project(), issue.issueRef(), planned.dependsOn(), false, false, false, 0);
            children.add(child);
            created.add(child);
            childRegistrar.accept(planned.project(), issue.issueRef());
            log.info("Foreman created child issue {}#{} extending {}", planned.project(), issue.issueRef(), ctx.issueRef());
        }

        storePlan(
            new FeaturePlan(
                plan != null ? plan.summary() : "",
                allIssues,
                plan != null ? plan.openQuestions() : List.of()
            )
        );
        ChildrenState newState = new ChildrenState(state.storyOwner(), state.storyRepo(), state.storyRef(), children);
        storeChildren(newState);

        // New issues whose dependencies are already merged start immediately
        for (var c : created) {
            if (dependenciesMerged(newState, c)) {
                assignToSmithy(c);
                newState = updateChild(newState, withAssigned(c));
            }
        }

        var sb = new StringBuilder();
        if (ext.reply() != null && !ext.reply().isBlank()) sb.append(ext.reply()).append("\n\n");
        if (created.isEmpty()) {
            sb.append("No issues could be created — the proposed targets are outside the manifest or the issue cap was reached.");
        } else {
            sb.append("Extended the plan — created ").append(created.size()).append(" issue(s):\n");
            for (var c : created) {
                Child current = findChild(newState, c.project(), c.issueRef());
                sb
                    .append("- ")
                    .append(c.project())
                    .append("#")
                    .append(c.issueRef())
                    .append(current != null && current.assigned() ? " (assigned to smithy)" : " (waiting on dependencies)")
                    .append("\n");
            }
        }
        storyTracker.createIssueComment(state.storyOwner(), state.storyRepo(), state.storyRef(), sb.toString());
    }

    private static String childStatus(Child c) {
        if (c.merged()) return "merged";
        if (c.approved()) return "implementation in progress (plan approved)";
        if (c.assigned()) return "assigned to smithy (planning)";
        return "waiting on dependencies";
    }

    /** Repo directory names present in this container's workspace. */
    private java.util.Set<String> clonedRepoNames() {
        var names = new java.util.HashSet<String>();
        names.add(repoName(manifest.repos().getFirst().project()));
        var result = session.exec("sh", "-c", "ls -1 repos 2>/dev/null || true");
        if (result.exitCode() == 0) {
            for (String line : result.stdout().split("\n")) {
                if (!line.isBlank()) names.add(line.trim());
            }
        }
        return names;
    }

    /** Clones manifest repos that joined the manifest after this container was created. */
    private void cloneMissingRepos(List<String> projects) {
        var present = clonedRepoNames();
        for (String project : projects) {
            if (!manifest.containsProject(project)) {
                log.warn("Foreman requested clone of {} which is not in the manifest, skipping", project);
                continue;
            }
            String name = repoName(project);
            if (present.contains(name)) continue;
            var result = session.exec("git", "clone", "--filter=blob:none", cloneUrlFor(project), "repos/" + name);
            if (result.exitCode() != 0) {
                log.warn("Foreman failed to clone {}: {}", project, result.stderr());
            } else {
                log.info("Foreman cloned {} into {}", project, session.getContainerName());
            }
        }
    }

    // ── EXECUTING: child plan review ─────────────────────────

    private void handleBotPlanPosted(WorkflowEvent.BotPlanPosted e) {
        // Smithy's plan announcement is the only bot issue comment worth reviewing
        if (!e.commentBody().contains("Development plan")) return;
        reviewChild(e.ctx().info().owner(), e.ctx().info().repo(), e.ctx().issueRef());
    }

    private void handleBotPush(WorkflowEvent.BotPush e) {
        String ref = Naming.parseIssueRefFromBranch(e.branch());
        if (ref == null) return;
        reviewChild(e.info().owner(), e.info().repo(), ref);
    }

    private void reviewChild(String owner, String repo, String issueRef) {
        try {
            var state = loadChildren();
            if (state == null) return;
            String project = owner + "/" + repo;
            Child child = findChild(state, project, issueRef);
            if (child == null || child.approved()) return;
            if (child.reviewRounds() >= MAX_REVIEW_ROUNDS) {
                log.info("Foreman review rounds exhausted for {}#{}, already escalated", project, issueRef);
                return;
            }

            String branch = vcsClient.findBranchByPrefix(owner, repo, "smithy/" + issueRef + "-");
            if (branch == null) {
                log.warn("Foreman found no smithy branch for {}#{}", project, issueRef);
                return;
            }
            String childPlan = vcsClient.getRawFile(owner, repo, branch, Naming.planFilePath(issueRef));
            if (childPlan == null || childPlan.isBlank()) {
                log.warn("Foreman found no plan file on {} for {}#{}", branch, project, issueRef);
                return;
            }

            FeaturePlan plan = loadPlan();
            var siblings = collectSiblingPlans(state, project, issueRef);
            String childIssueTitle = plan != null && child.planIndex() < plan.issues().size()
                ? plan.issues().get(child.planIndex()).title()
                : "";

            var vars = new HashMap<String, Object>();
            vars.put("story_ref", state.storyRef());
            vars.put("child_project", project);
            vars.put("child_ref", issueRef);
            vars.put("child_issue_title", childIssueTitle);
            vars.put("child_plan", childPlan);
            vars.put("feature_plan", plan != null ? renderPlanComment(plan) : "");
            vars.put("siblings", siblings);
            vars.put("round", child.reviewRounds() + 1);

            ChildPlanVerdict verdict = claude.send(renderer.render("foreman_review_child_plan.md.j2", vars), ChildPlanVerdict.class);
            syncSessionId();
            session.updateState(ContainerState::touch);

            if (verdict.aligned()) {
                approveChild(state, child);
            } else {
                requestChanges(state, child, verdict.feedback());
            }
        } catch (Exception ex) {
            log.error("Foreman child plan review failed for {}/{}#{}", owner, repo, issueRef, ex);
        }
    }

    private void approveChild(ChildrenState state, Child child) throws Exception {
        String[] parts = child.project().split("/", 2);
        if (foremanConfig.isGated()) {
            storyTracker.createIssueComment(
                state.storyOwner(),
                state.storyRepo(),
                state.storyRef(),
                ("Plan for %s#%s looks aligned with the feature plan. Add the `%s` label on that issue to unlock " +
                    "implementation (gated mode).").formatted(child.project(), child.issueRef(), PLAN_APPROVED_LABEL)
            );
            updateChild(state, withRounds(child, child.reviewRounds() + 1));
            return;
        }
        childIssueTracker.addIssueLabel(parts[0], parts[1], child.issueRef(), PLAN_APPROVED_LABEL);
        updateChild(state, withApproved(child));
        storyTracker.createIssueComment(
            state.storyOwner(),
            state.storyRepo(),
            state.storyRef(),
            "Approved smithy's plan for %s#%s — implementation started.".formatted(child.project(), child.issueRef())
        );
        log.info("Foreman approved plan for {}#{}", child.project(), child.issueRef());
    }

    private void requestChanges(ChildrenState state, Child child, String feedback) throws Exception {
        String[] parts = child.project().split("/", 2);
        int rounds = child.reviewRounds() + 1;
        if (rounds >= MAX_REVIEW_ROUNDS) {
            storyTracker.createIssueComment(
                state.storyOwner(),
                state.storyRepo(),
                state.storyRef(),
                ("Plan review for %s#%s has gone %d rounds without converging — needs human input. " +
                    "Latest concern:\n\n%s").formatted(child.project(), child.issueRef(), rounds, feedback)
            );
        } else if (feedback != null && !feedback.isBlank()) {
            childIssueTracker.createIssueComment(parts[0], parts[1], child.issueRef(), feedback);
        }
        updateChild(state, withRounds(child, rounds));
        log.info("Foreman requested plan changes on {}#{} (round {})", child.project(), child.issueRef(), rounds);
    }

    private List<Map<String, String>> collectSiblingPlans(ChildrenState state, String exceptProject, String exceptRef) {
        var siblings = new ArrayList<Map<String, String>>();
        for (var c : state.children()) {
            if (c.project().equals(exceptProject) && c.issueRef().equals(exceptRef)) continue;
            try {
                String[] parts = c.project().split("/", 2);
                String branch = vcsClient.findBranchByPrefix(parts[0], parts[1], "smithy/" + c.issueRef() + "-");
                if (branch == null) continue;
                String plan = vcsClient.getRawFile(parts[0], parts[1], branch, Naming.planFilePath(c.issueRef()));
                if (plan == null || plan.isBlank()) continue;
                siblings.add(Map.of("project", c.project(), "ref", c.issueRef(), "plan", plan));
            } catch (Exception ex) {
                log.warn("Failed to fetch sibling plan for {}#{}", c.project(), c.issueRef(), ex);
            }
        }
        return siblings;
    }

    // ── EXECUTING: wave progression ──────────────────────────

    private void handleChildMerged(WorkflowEvent.PrMerged e) {
        String ref = Naming.parseIssueRefFromBranch(e.prc().headBranch());
        if (ref == null) return;
        String project = e.prc().info().owner() + "/" + e.prc().info().repo();
        try {
            var state = loadChildren();
            if (state == null) return;
            Child child = findChild(state, project, ref);
            if (child == null || child.merged()) return;

            state = updateChild(state, withMerged(child));
            log.info("Foreman marked {}#{} merged", project, ref);

            // Assign every unassigned child whose dependencies are all merged
            var newlyAssigned = new ArrayList<Child>();
            for (var c : state.children()) {
                if (c.assigned() || c.merged()) continue;
                if (dependenciesMerged(state, c)) {
                    assignToSmithy(c);
                    state = updateChild(state, withAssigned(c));
                    newlyAssigned.add(c);
                }
            }

            if (!newlyAssigned.isEmpty()) {
                var sb = new StringBuilder(project + "#" + ref + " merged — next wave assigned:\n");
                for (var c : newlyAssigned) sb.append("- ").append(c.project()).append("#").append(c.issueRef()).append("\n");
                storyTracker.createIssueComment(state.storyOwner(), state.storyRepo(), state.storyRef(), sb.toString());
            }

            if (state.children().stream().allMatch(Child::merged)) {
                session.updateState(s -> s.withStage(ForemanStage.DONE.value()).touch());
                storyTracker.createIssueComment(
                    state.storyOwner(),
                    state.storyRepo(),
                    state.storyRef(),
                    "All child merge requests are merged — this feature is complete."
                );
                log.info("Foreman feature {} complete", state.storyRef());
            }
        } catch (Exception ex) {
            log.error("Foreman wave progression failed after {}#{} merged", project, ref, ex);
        }
    }

    private boolean dependenciesMerged(ChildrenState state, Child child) {
        for (int dep : child.dependsOn()) {
            boolean depMerged = state
                .children()
                .stream()
                .filter(c -> c.planIndex() == dep)
                .findFirst()
                .map(Child::merged)
                // A dependency that was never created (cap/manifest skip) cannot block forever
                .orElse(true);
            if (!depMerged) return false;
        }
        return true;
    }

    private void assignToSmithy(Child child) {
        String[] parts = child.project().split("/", 2);
        childIssueTracker.setIssueAssignees(parts[0], parts[1], child.issueRef(), List.of(smithyBotUser));
    }

    // ── Plan drafting & persistence ──────────────────────────

    private FeaturePlan draftPlan(String storyRef, String title, String body, String feedback, List<String> attachments) {
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
        vars.put("attachments", attachments != null ? attachments : List.of());

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

    private void storeChildren(ChildrenState state) throws Exception {
        session.copyToContainer("/tmp", MAPPER.writeValueAsBytes(state), "foreman-children.json");
    }

    public ChildrenState loadChildren() {
        try {
            var result = session.exec("cat", CHILDREN_PATH);
            if (result.exitCode() != 0) return null;
            return MAPPER.readValue(result.stdout().getBytes(StandardCharsets.UTF_8), ChildrenState.class);
        } catch (Exception e) {
            log.warn("Failed to load foreman children from {}", session.getContainerName(), e);
            return null;
        }
    }

    /** Replace the entry matching the child's identity and persist; returns the new state. */
    private ChildrenState updateChild(ChildrenState state, Child updated) throws Exception {
        var children = new ArrayList<Child>();
        for (var c : state.children()) {
            children.add(c.project().equals(updated.project()) && c.issueRef().equals(updated.issueRef()) ? updated : c);
        }
        var newState = new ChildrenState(state.storyOwner(), state.storyRepo(), state.storyRef(), children);
        storeChildren(newState);
        return newState;
    }

    private static Child findChild(ChildrenState state, String project, String issueRef) {
        return state
            .children()
            .stream()
            .filter(c -> c.project().equals(project) && c.issueRef().equals(issueRef))
            .findFirst()
            .orElse(null);
    }

    private static Child withAssigned(Child c) {
        return new Child(c.planIndex(), c.project(), c.issueRef(), c.dependsOn(), true, c.merged(), c.approved(), c.reviewRounds());
    }

    private static Child withMerged(Child c) {
        return new Child(c.planIndex(), c.project(), c.issueRef(), c.dependsOn(), c.assigned(), true, c.approved(), c.reviewRounds());
    }

    private static Child withApproved(Child c) {
        return new Child(c.planIndex(), c.project(), c.issueRef(), c.dependsOn(), c.assigned(), c.merged(), true, c.reviewRounds());
    }

    private static Child withRounds(Child c, int rounds) {
        return new Child(c.planIndex(), c.project(), c.issueRef(), c.dependsOn(), c.assigned(), c.merged(), c.approved(), rounds);
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
