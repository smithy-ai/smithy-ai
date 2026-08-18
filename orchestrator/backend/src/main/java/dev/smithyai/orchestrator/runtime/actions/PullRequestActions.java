package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.service.vcs.VcsClients;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Pull-request actions — one provider call each, grouped for the same reason as {@link IssueActions}. */
@Slf4j
@Configuration
public class PullRequestActions {

    /**
     * Open a pull request.
     *
     * <p>Not idempotent, and the reason the step executor records outputs: a
     * transition interrupted after this step and replayed would otherwise open a
     * second one. Where the provider already has a PR for the branch it is
     * reused rather than duplicated, which covers the case where the crash
     * landed between the provider call and the record of it.
     */
    @Bean
    public WorkflowAction prCreateAction(VcsClients clients) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "pr.create";
            }

            @Override
            public Set<Capability> requires() {
                return Set.of(Capability.PR_CREATE);
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var vcs = Vcs.pick(this, context, input, clients);
                String owner = required(input, "owner");
                String repo = required(input, "repo");
                String head = required(input, "head");

                var existing = vcs.findPrByHead(owner, repo, head);
                var pr =
                    existing != null
                        ? existing
                        : vcs.createPullRequest(
                              owner,
                              repo,
                              required(input, "title"),
                              head,
                              required(input, "base"),
                              optional(input, "body", ""),
                              boolInput(input, "draft", false)
                          );
                if (existing != null) {
                    log.info("Reusing existing PR #{} for {}/{}:{}", pr.number(), owner, repo, head);
                }

                var output = new LinkedHashMap<String, Object>();
                output.put("number", pr.number());
                output.put("title", pr.title());
                output.put("headRef", pr.headRef());
                output.put("baseRef", pr.baseRef());
                output.put("reused", existing != null);
                return output;
            }
        };
    }

    @Bean
    public WorkflowAction prCommentAction(VcsClients clients) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "pr.comment";
            }

            @Override
            public Set<Capability> requires() {
                return Set.of(Capability.PR_COMMENT);
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var vcs = Vcs.pick(this, context, input, clients);
                int number = intInput(input, "number", -1);
                if (number < 0) throw new IllegalArgumentException("pr.comment requires 'number'");
                vcs.createPrComment(required(input, "owner"), required(input, "repo"), number, required(input, "body"));
                return Map.of("number", number);
            }
        };
    }

    @Bean
    public WorkflowAction prRequestReviewAction(VcsClients clients) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "pr.requestReview";
            }

            @Override
            public Set<Capability> requires() {
                return Set.of(Capability.PR_REQUEST_REVIEW);
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var vcs = Vcs.pick(this, context, input, clients);
                int number = intInput(input, "number", -1);
                if (number < 0) throw new IllegalArgumentException("pr.requestReview requires 'number'");

                // Never the author. Providers reject it, and the request was
                // only ever a courtesy — the approver is often the person who
                // asked for the work, and sometimes the agent itself.
                String target = Vcs.target(this, context, input, clients);
                String excludedActor = optional(input, "notFromActor", "");
                String excludedUsername = excludedActor.isBlank() ? "" : clients.username(target, excludedActor);
                var requestedReviewers = new java.util.ArrayList<>(listInput(input, "reviewers"));
                requestedReviewers.addAll(
                    listInput(input, "actors")
                        .stream()
                        .map(actor -> clients.username(target, actor))
                        .toList()
                );
                var reviewers = requestedReviewers
                    .stream()
                    .filter(reviewer -> !reviewer.isBlank() && !reviewer.equals(excludedUsername))
                    .distinct()
                    .toList();
                if (reviewers.isEmpty()) return Map.of("number", number, "requested", false, "reason", "no-one to ask");

                try {
                    vcs.requestReview(required(input, "owner"), required(input, "repo"), number, reviewers);
                    return Map.of("number", number, "reviewers", reviewers, "requested", true);
                } catch (RuntimeException e) {
                    // Reported, never thrown: the branch is pushed and the pull
                    // request is open, and failing here would abandon both over
                    // a notification.
                    log.warn("Could not request review on PR #{} from {}: {}", number, reviewers, e.getMessage());
                    return Map.of("number", number, "requested", false, "reason", String.valueOf(e.getMessage()));
                }
            }
        };
    }

    /**
     * Read a pull request back from the provider.
     *
     * <p>Ground truth, and the reason it is a step rather than something the
     * webhook adapter resolves: fetching it on the webhook thread blocked
     * ingestion on a provider round trip for every event.
     */
    @Bean
    public WorkflowAction prReadAction(VcsClients clients) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "pr.read";
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var vcs = Vcs.pick(this, context, input, clients);
                int number = intInput(input, "number", -1);
                if (number < 0) throw new IllegalArgumentException("pr.read requires 'number'");
                var pr = vcs.getPullRequest(required(input, "owner"), required(input, "repo"), number);
                var output = new LinkedHashMap<String, Object>();
                output.put("number", pr.number());
                output.put("title", pr.title());
                output.put("body", pr.body());
                output.put("merged", pr.merged());
                output.put("headRef", pr.headRef());
                output.put("baseRef", pr.baseRef());
                output.put("assignees", pr.assignees());
                return output;
            }
        };
    }
}
