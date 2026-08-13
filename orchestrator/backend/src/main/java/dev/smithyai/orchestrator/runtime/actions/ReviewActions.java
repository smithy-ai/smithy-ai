package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.model.CommentData;
import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.service.vcs.AttachmentHelper;
import dev.smithyai.orchestrator.service.vcs.IssueTrackers;
import dev.smithyai.orchestrator.service.vcs.VcsClients;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The actions a code-review conversation needs.
 *
 * <p>Everything here exists because the hardcoded flows did it inline: reacting
 * to a comment before a long turn so the reviewer knows they were heard,
 * threading a reply back into the discussion it answers, and pulling a review's
 * comments into a shape a prompt can iterate.
 */
@Slf4j
@Configuration
public class ReviewActions {

    /**
     * A "seen it" reaction on the comment that triggered the event.
     *
     * <p>Posted before the turn it triggers, which can run for half an hour.
     * Best-effort: a provider without reactions leaves it a no-op, and failing
     * to acknowledge must never stop the work.
     */
    @Bean
    public WorkflowAction commentReactAction(VcsClients clients) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "comment.react";
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var vcs = Vcs.pick(this, context, input, clients);
                long commentId = intInput(input, "commentId", 0);
                if (commentId <= 0) return Map.of("reacted", false);
                try {
                    vcs.reactToPrComment(
                        required(input, "owner"),
                        required(input, "repo"),
                        intInput(input, "number", 0),
                        commentId,
                        optional(input, "reaction", "eyes")
                    );
                    return Map.of("reacted", true);
                } catch (RuntimeException e) {
                    log.debug("Could not react to comment {}: {}", commentId, e.getMessage());
                    return Map.of("reacted", false);
                }
            }
        };
    }

    /**
     * Answer a review comment where it was made.
     *
     * <p>A single comment gets its reply threaded into its own discussion; a
     * batch gets one top-level reply covering all of them, because threading one
     * answer into an arbitrary member of a burst reads as a non-sequitur.
     */
    @Bean
    public WorkflowAction prReplyAction(VcsClients clients) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "pr.reply";
            }

            @Override
            public Set<Capability> requires() {
                return Set.of(Capability.PR_COMMENT);
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var vcs = Vcs.pick(this, context, input, clients);
                String body = optional(input, "body", "");
                if (body.isBlank()) return Map.of("posted", false);

                String owner = required(input, "owner");
                String repo = required(input, "repo");
                int number = intInput(input, "number", 0);
                String discussion = optional(input, "discussion", null);
                if (discussion != null) {
                    vcs.replyToPrDiscussion(owner, repo, number, discussion, body);
                } else {
                    vcs.createPrComment(owner, repo, number, body);
                }
                return Map.of("posted", true, "threaded", discussion != null);
            }
        };
    }

    /** Whether the bot is still assigned — a human unassigning it is how they take over. */
    @Bean
    public WorkflowAction prIsAssignedAction(VcsClients clients) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "pr.isAssigned";
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var vcs = Vcs.pick(this, context, input, clients);
                boolean assigned = vcs.isAssigned(
                    required(input, "owner"),
                    required(input, "repo"),
                    intInput(input, "number", 0),
                    required(input, "user")
                );
                return Map.of("assigned", assigned);
            }
        };
    }

    @Bean
    public WorkflowAction prSetAssigneesAction(VcsClients clients) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "pr.setAssignees";
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var vcs = Vcs.pick(this, context, input, clients);
                var assignees = listInput(input, "assignees");
                vcs.setPrAssignees(
                    required(input, "owner"),
                    required(input, "repo"),
                    intInput(input, "number", 0),
                    assignees
                );
                return Map.of("assignees", assignees);
            }
        };
    }

    /** Find the pull request for a branch, if there is one. */
    @Bean
    public WorkflowAction prFindByHeadAction(VcsClients clients) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "pr.findByHead";
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var vcs = Vcs.pick(this, context, input, clients);
                var pr = vcs.findPrByHead(required(input, "owner"), required(input, "repo"), required(input, "head"));
                if (pr == null) return Map.of("found", false);
                return Map.of(
                    "found",
                    true,
                    "number",
                    pr.number(),
                    "title",
                    pr.title(),
                    "merged",
                    pr.merged(),
                    "assignees",
                    pr.assignees() == null ? List.of() : pr.assignees()
                );
            }
        };
    }

    /**
     * A review's comments, in the shape a prompt iterates.
     *
     * <p>Providers differ on whether a review webhook carries an id you can look
     * the comments up by; when it does not, the reviewer's latest review is
     * fetched instead. Both come back the same way, so the definition does not
     * have to know which happened.
     */
    @Bean
    public WorkflowAction prReviewCommentsAction(VcsClients clients) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "pr.reviewComments";
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var vcs = Vcs.pick(this, context, input, clients);
                String owner = required(input, "owner");
                String repo = required(input, "repo");
                int number = intInput(input, "number", 0);
                long reviewId = intInput(input, "reviewId", 0);
                String reviewer = optional(input, "reviewer", "");

                String body = optional(input, "body", "");
                List<dev.smithyai.orchestrator.service.vcs.dto.ReviewCommentEntry> entries;
                if (reviewId > 0) {
                    entries = vcs.getReviewComments(owner, repo, number, reviewId);
                } else {
                    var latest = vcs.getLatestReviewComments(owner, repo, number, reviewer);
                    entries = latest.comments();
                    body = latest.reviewBody();
                }

                var comments = new ArrayList<Map<String, Object>>();
                if (body != null && !body.isBlank()) {
                    comments.add(CommentData.conversation(reviewer, body).toMap());
                }
                for (var entry : entries) {
                    comments.add(
                        new CommentData(
                            entry.userLogin(),
                            entry.body(),
                            entry.path() == null ? "" : entry.path(),
                            (int) entry.position()
                        ).toMap()
                    );
                }
                return Map.of("comments", comments, "count", comments.size());
            }
        };
    }

    /**
     * Pull an issue's attachments into the container.
     *
     * <p>Designs and mockups carry requirements the issue text omits, and the
     * agent cannot follow a link out of its container — so the files come in and
     * the prompt names them by path.
     */
    @Bean
    public WorkflowAction attachmentsFetchAction(IssueTrackers trackers, RunEnvironments environments) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "attachments.fetch";
            }

            @Override
            public Set<Capability> requires() {
                return Set.of(Capability.ENVIRONMENT);
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var session = environments.container(context.run());
                var paths = AttachmentHelper.fetchAndInject(
                    Trackers.pick(this, context, input, trackers),
                    session,
                    required(input, "owner"),
                    required(input, "repo"),
                    required(input, "issue")
                );
                return Map.of("paths", paths, "count", paths.size());
            }
        };
    }

    /**
     * Remove a file through the provider API.
     *
     * <p>For when the container that would have done it is already gone — a plan
     * file left on a branch would otherwise block the merge it was written for.
     */
    @Bean
    public WorkflowAction fileDeleteAction(VcsClients clients) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "file.delete";
            }

            @Override
            public Set<Capability> requires() {
                return Set.of(Capability.FILE_DELETE, Capability.FILE_READ);
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var vcs = Vcs.pick(this, context, input, clients);
                String owner = required(input, "owner");
                String repo = required(input, "repo");
                String branch = required(input, "branch");
                String path = required(input, "path");
                if (vcs.getRawFile(owner, repo, branch, path) == null) return Map.of("deleted", false);
                vcs.deleteFile(owner, repo, branch, path, optional(input, "message", "Remove " + path));
                return Map.of("deleted", true);
            }
        };
    }

    /**
     * Post a review: a summary plus comments anchored to lines.
     *
     * <p>Inline comments are the point — a review that can only leave one
     * top-level note makes the reader find every line it refers to. Comments
     * without a path are dropped rather than posted somewhere arbitrary, and a
     * review with nothing in it is not posted at all.
     */
    @Bean
    public WorkflowAction prReviewAction(VcsClients clients) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "pr.review";
            }

            @Override
            public Set<Capability> requires() {
                return Set.of(Capability.PR_REVIEW_INLINE);
            }

            @Override
            @SuppressWarnings("unchecked")
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var vcs = Vcs.pick(this, context, input, clients);
                String summary = optional(input, "summary", "");
                var inline = new ArrayList<dev.smithyai.orchestrator.service.vcs.dto.InlineComment>();
                if (input.get("comments") instanceof List<?> comments) {
                    for (Object entry : comments) {
                        if (!(entry instanceof Map<?, ?> comment)) continue;
                        var fields = (Map<String, Object>) comment;
                        String path = optional(fields, "path", "");
                        if (path.isBlank()) continue;
                        inline.add(
                            new dev.smithyai.orchestrator.service.vcs.dto.InlineComment(
                                path,
                                optional(fields, "body", ""),
                                intInput(fields, "line", 0)
                            )
                        );
                    }
                }
                if (summary.isBlank() && inline.isEmpty()) return Map.of("posted", false, "comments", 0);

                vcs.createPullReview(
                    required(input, "owner"),
                    required(input, "repo"),
                    intInput(input, "number", 0),
                    summary,
                    optional(input, "event", "COMMENT"),
                    inline.isEmpty() ? null : inline
                );
                return Map.of("posted", true, "comments", inline.size());
            }
        };
    }

    /**
     * Where a repository is cloned from.
     *
     * <p>A coordinator's workspace comes from its catalog, not from the story:
     * a story in Jira has no repository behind it at all, and taking the clone
     * URL from the event meant trying to clone the string "null".
     */
    @Bean
    public WorkflowAction repoCloneUrlAction(VcsClients clients) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "repo.cloneUrl";
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var vcs = Vcs.pick(this, context, input, clients);
                String owner = required(input, "owner");
                String repo = required(input, "repo");
                return Map.of("cloneUrl", vcs.cloneUrl(owner, repo), "fullName", owner + "/" + repo);
            }
        };
    }

    /** A link to a pull request that works outside the network the orchestrator is on. */
    @Bean
    public WorkflowAction prLinkAction(
        VcsClients clients,
        dev.smithyai.orchestrator.config.VcsProviderConfig vcsConfig
    ) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "pr.link";
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var vcs = Vcs.pick(this, context, input, clients);
                return Map.of(
                    "url",
                    vcs.prUrl(
                        vcsConfig.resolvedExternalUrl(),
                        required(input, "owner"),
                        required(input, "repo"),
                        intInput(input, "number", 0)
                    )
                );
            }
        };
    }

    /**
     * A browsable link to a file on a branch.
     *
     * <p>Takes the repository rather than a URL: the obvious thing to pass is
     * the clone URL, and on Forgejo that produces links with ".git" in the
     * middle of them that resolve to nothing.
     */
    @Bean
    public WorkflowAction fileUrlAction(
        VcsClients clients,
        dev.smithyai.orchestrator.config.VcsProviderConfig vcsConfig
    ) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "file.url";
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var vcs = Vcs.pick(this, context, input, clients);
                String root = "%s/%s/%s".formatted(
                    vcsConfig.resolvedExternalUrl(),
                    required(input, "owner"),
                    required(input, "repo")
                );
                var url = new LinkedHashMap<String, Object>();
                url.put("url", vcs.fileBrowseUrl(root, required(input, "branch"), required(input, "path")));
                return url;
            }
        };
    }
}
