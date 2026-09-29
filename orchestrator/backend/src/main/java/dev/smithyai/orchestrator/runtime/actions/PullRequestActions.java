package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.runtime.store.Run;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import dev.smithyai.orchestrator.service.vcs.VcsClients;
import dev.smithyai.orchestrator.web.WebhookArrivals;
import java.time.Duration;
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

    /** The run variable, and dashboard flag, set when a pull request cannot report back. */
    public static final String WEBHOOK_MISSING_VAR = "webhookMissing";

    /** How long {@code pr.create} waits to hear its pull request was opened, unless told otherwise. */
    static final Duration DEFAULT_WEBHOOK_PATIENCE = Duration.ofMinutes(1);

    /**
     * Open a pull request.
     *
     * <p>Not idempotent, and the reason the step executor records outputs: a
     * transition interrupted after this step and replayed would otherwise open a
     * second one. Where the provider already has a PR for the branch it is
     * reused rather than duplicated, which covers the case where the crash
     * landed between the provider call and the record of it.
     *
     * <p>A newly opened pull request is then listened for. A pull request in a
     * repository whose events do not reach this orchestrator is work delivered
     * somewhere it is deaf to: every review comment, every "@bot fix this"
     * lands in silence, and nothing tells the people commenting. Observed
     * live: 132 of 138 catalog repositories in one deployment, and comments on
     * merge requests there went unanswered for days. Whether the events reach
     * us is judged from the one delivery that is certain to be attempted — the
     * opening itself — rather than from the repository's hook list, which is
     * empty wherever the hook lives on the group or organisation.
     */
    @Bean
    public WorkflowAction prCreateAction(VcsClients clients, WebhookArrivals arrivals, RunStore store) {
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

                // Once, when the pull request opens; a reused one has had its say.
                boolean webhookMissing = false;
                Duration patience = existing == null ? patience(input) : Duration.ZERO;
                if (!patience.isZero() && !arrivals.awaitPullRequest(owner, repo, pr.number(), patience)) {
                    webhookMissing = true;
                    String connector = Vcs.target(this, context, input, clients);
                    log.warn(
                        "No webhook delivery about {}/{} PR #{} reached /webhooks/{} within {}: comments there will not either",
                        owner,
                        repo,
                        pr.number(),
                        connector,
                        patience
                    );
                    try {
                        vcs.createPrComment(owner, repo, pr.number(), notice(connector, patience));
                    } catch (RuntimeException e) {
                        // A courtesy; the pull request itself is what matters.
                        log.warn("Could not post the webhook notice on {}/{} PR #{}", owner, repo, pr.number(), e);
                    }
                    flag(store, context.run(), connector, owner, repo, pr.number(), patience);
                }

                var output = new LinkedHashMap<String, Object>();
                output.put("number", pr.number());
                output.put("title", pr.title());
                output.put("headRef", pr.headRef());
                output.put("baseRef", pr.baseRef());
                output.put("reused", existing != null);
                output.put(WEBHOOK_MISSING_VAR, webhookMissing);
                return output;
            }

            /** {@code webhookTimeout}: how long to listen for the opening; {@code 0} does not listen. */
            private Duration patience(Map<String, Object> input) {
                String raw = optional(input, "webhookTimeout", "");
                if (raw.isBlank()) return DEFAULT_WEBHOOK_PATIENCE;
                try {
                    String value = raw.strip();
                    if (value.regionMatches(true, 0, "P", 0, 1)) return Duration.parse(value);
                    char unit = value.charAt(value.length() - 1);
                    if (Character.isDigit(unit)) return Duration.ofSeconds(Long.parseLong(value));
                    long amount = Long.parseLong(value.substring(0, value.length() - 1).strip());
                    return switch (Character.toLowerCase(unit)) {
                        case 's' -> Duration.ofSeconds(amount);
                        case 'm' -> Duration.ofMinutes(amount);
                        case 'h' -> Duration.ofHours(amount);
                        default -> throw new IllegalArgumentException("unknown unit '" + unit + "'");
                    };
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException(
                        "%s expects a duration for 'webhookTimeout' (e.g. 60s, 2m, PT1M or 0), got '%s'".formatted(
                            type(),
                            raw
                        ),
                        e
                    );
                }
            }
        };
    }

    /** What the people on the pull request are told when the bot cannot hear them. */
    static String notice(String connector, Duration waited) {
        return (
            "Heads-up: nothing said here will reach me. I opened this pull request and waited %s for the " +
            "provider to tell the orchestrator about it at `/webhooks/%s`, and nothing arrived — so comments, " +
            "reviews and pipeline results posted here will not arrive either. A maintainer can add a webhook " +
            "for this repository, or for its group or organisation, pointing there, with merge/pull request " +
            "and comment events enabled and the configured secret. Until then, reach me on the issue or from " +
            "the dashboard."
        ).formatted(humane(waited), connector);
    }

    /**
     * Mark the run, so the dashboard shows the pull request that cannot report
     * back and the timeline says which one and when.
     */
    private static void flag(
        RunStore store,
        Run run,
        String connector,
        String owner,
        String repo,
        int number,
        Duration waited
    ) {
        if (store == null || run == null) return;
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("owner", owner);
            payload.put("repo", repo);
            payload.put("number", number);
            payload.put("connector", connector);
            payload.put("waited", waited.toString());
            store.appendEvent(run.id(), "webhook.missing", payload);
            store.mergeVars(run.id(), Map.of(WEBHOOK_MISSING_VAR, true));
        } catch (RuntimeException e) {
            log.warn("Could not flag run {} for the missing webhook on {}/{} PR #{}", run.id(), owner, repo, number, e);
        }
    }

    private static String humane(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds >= 60 && seconds % 60 == 0) {
            long minutes = seconds / 60;
            return minutes == 1 ? "a minute" : minutes + " minutes";
        }
        return seconds == 1 ? "a second" : seconds + " seconds";
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
