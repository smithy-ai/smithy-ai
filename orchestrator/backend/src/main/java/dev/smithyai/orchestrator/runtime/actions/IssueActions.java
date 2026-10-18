package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.service.vcs.IssueTrackers;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Issue-tracker actions.
 *
 * <p>These are thin by design — each is one provider call with its arguments
 * read out of a step's {@code with:} block — so they are grouped rather than
 * spread over a file each. Anything that needs real logic gets its own class.
 */
@Slf4j
@Configuration
public class IssueActions {

    /**
     * Create an issue in a repository.
     *
     * <p>This is how a coordinator fans work out. Deliberately an ordinary issue
     * rather than a tracker-native subtask: not every tracker has them, and a
     * parent story may live in Jira while the work lives in VCS repositories.
     * The parent link is recorded in the run store by {@code correlate}.
     */
    @Bean
    public WorkflowAction issueCreateAction(IssueTrackers trackers) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "issue.create";
            }

            @Override
            public Set<Capability> requires() {
                return Set.of(Capability.ISSUE_CREATE);
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var created = Trackers.pick(this, context, input, trackers).createIssue(
                    required(input, "owner"),
                    required(input, "repo"),
                    required(input, "title"),
                    optional(input, "body", ""),
                    listInput(input, "labels")
                );
                var output = new LinkedHashMap<String, Object>();
                output.put("issueRef", created.issueRef());
                output.put("title", created.title());
                output.put("baseBranch", created.baseBranch());
                return output;
            }
        };
    }

    /** Assign an issue — how a coordinator hands a child issue to the bot. */
    @Bean
    public WorkflowAction issueAssignAction(IssueTrackers trackers) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "issue.assign";
            }

            @Override
            public Set<Capability> requires() {
                return Set.of(Capability.ISSUE_ASSIGN);
            }

            @Override
            public boolean idempotent() {
                // Setting the same assignees again is a no-op at the provider.
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                String target = Trackers.target(this, context, input);
                List<String> actors = requiredListInput(input, "actors");
                List<String> assignees = actors
                    .stream()
                    .map(actor -> trackers.assignee(target, actor))
                    .toList();
                Trackers.pick(this, context, input, trackers).setIssueAssignees(
                    required(input, "owner"),
                    required(input, "repo"),
                    required(input, "issue"),
                    assignees
                );
                return Map.of("actors", actors, "assignees", assignees);
            }
        };
    }

    /**
     * Read an issue's comment thread.
     *
     * <p>Decisions live in the discussion, not the description: "skip that part
     * for now", "these two must ship together". A planning turn that reads only
     * the description re-litigates every one of them after a reset — this is
     * how a workflow hands the agent the conversation so far.
     */
    @Bean
    public WorkflowAction issueCommentsAction(IssueTrackers trackers) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "issue.comments";
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var comments = Trackers.pick(this, context, input, trackers)
                    .getIssueComments(required(input, "owner"), required(input, "repo"), required(input, "issue"))
                    .stream()
                    .map(comment -> {
                        var entry = new LinkedHashMap<String, Object>();
                        // The id is what a later step needs to keep or remove
                        // one comment rather than describe it.
                        entry.put("id", comment.id());
                        entry.put("author", comment.userLogin());
                        entry.put("body", comment.body());
                        entry.put("createdAt", String.valueOf(comment.createdAt()));
                        return entry;
                    })
                    .toList();
                return Map.of("comments", comments, "count", comments.size());
            }
        };
    }

    /**
     * Remove the bot's own comments on an issue, except the ones named.
     *
     * <p>A planning conversation leaves a trail: an acknowledgement, a plan,
     * a revised plan after every answer, each posted in full. By the time a
     * plan is approved the issue holds several near-identical walls of text
     * and the one that counts is indistinguishable from the ones it replaced.
     * Observed live: readers missed answers and decisions buried between plan
     * versions. This deletes what the workflow's own identity wrote, keeps
     * what {@code keep} names — the approved plan, a digest — and never
     * touches a human's comment.
     *
     * <p>Best-effort per comment: a deletion the provider refuses is counted
     * and logged, not thrown, because tidying up must not undo an approval.
     */
    @Bean
    public WorkflowAction issuePruneCommentsAction(IssueTrackers trackers) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "issue.pruneComments";
            }

            @Override
            public Set<Capability> requires() {
                return Set.of(Capability.ISSUE_COMMENT_DELETE);
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                String owner = required(input, "owner");
                String repo = required(input, "repo");
                String issueRef = required(input, "issue");
                var keep = listInput(input, "keep")
                    .stream()
                    .map(String::strip)
                    .filter(id -> !id.isEmpty())
                    .collect(java.util.stream.Collectors.toSet());

                // Whose comments: the workflow's actor as this tracker knows it
                // (a Jira accountId, a GitLab username), unless the step names
                // an identity itself.
                String target = Trackers.target(this, context, input);
                String actor = optional(input, "actor", context.actor());
                String author = optional(input, "author", trackers.assignee(target, actor));
                if (author == null || author.isBlank()) {
                    throw new IllegalArgumentException(
                        type() + " cannot tell which comments are its own: no identity for actor '" + actor + "'"
                    );
                }

                var tracker = trackers.forConnector(actor, target);
                var deleted = new java.util.ArrayList<Long>();
                var kept = new java.util.ArrayList<Long>();
                int failed = 0;
                for (var comment : tracker.getIssueComments(owner, repo, issueRef)) {
                    if (!author.equals(comment.userLogin())) continue;
                    if (keep.contains(String.valueOf(comment.id()))) {
                        kept.add(comment.id());
                        continue;
                    }
                    try {
                        tracker.deleteIssueComment(owner, repo, issueRef, comment.id());
                        deleted.add(comment.id());
                    } catch (RuntimeException e) {
                        failed++;
                        log.warn("Could not delete comment {} on {}/{}#{}", comment.id(), owner, repo, issueRef, e);
                    }
                }
                log.info(
                    "Pruned {} of {}'s comment(s) on {}/{}#{} ({} kept, {} failed)",
                    deleted.size(),
                    actor,
                    owner,
                    repo,
                    issueRef,
                    kept.size(),
                    failed
                );
                var output = new LinkedHashMap<String, Object>();
                output.put("deleted", deleted.size());
                output.put("deletedIds", deleted);
                output.put("kept", kept);
                output.put("failed", failed);
                return output;
            }
        };
    }

    @Bean
    public WorkflowAction issueLabelAction(IssueTrackers trackers) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "issue.label";
            }

            @Override
            public Set<Capability> requires() {
                return Set.of(Capability.ISSUE_LABEL);
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                String owner = required(input, "owner");
                String repo = required(input, "repo");
                String issue = required(input, "issue");
                var labels = listInput(input, "labels");
                if (labels.isEmpty()) labels = List.of(required(input, "label"));
                var tracker = Trackers.pick(this, context, input, trackers);
                labels.forEach(label -> tracker.addIssueLabel(owner, repo, issue, label));
                return Map.of("labels", labels);
            }
        };
    }

    /** Read an issue back from the tracker, which is ground truth for its state. */
    @Bean
    public WorkflowAction issueReadAction(IssueTrackers trackers) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "issue.read";
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var issue = Trackers.pick(this, context, input, trackers).getIssue(
                    required(input, "owner"),
                    required(input, "repo"),
                    required(input, "issue")
                );
                var output = new LinkedHashMap<String, Object>();
                output.put("issueRef", issue.issueRef());
                output.put("title", issue.title());
                output.put("body", issue.body());
                output.put("state", issue.state());
                output.put("assignees", issue.assignees());
                output.put("labels", issue.labels());
                output.put("baseBranch", issue.baseBranch());
                return output;
            }
        };
    }
}
