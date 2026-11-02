package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.VcsClients;
import dev.smithyai.orchestrator.service.vcs.dto.DiscussionEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Everything said on a pull request, in the order it was said.
 *
 * <p>Top-level comments, review summaries and the comments anchored to lines,
 * merged and sorted. What was argued about on the way to a merge is where the
 * durable lesson is — the diff only shows what was decided, not why.
 *
 * <p>Each source is fetched independently and a failure in one costs only that
 * source: an incomplete conversation is worth more than none.
 */
@Slf4j
@Component
public class PrConversationAction implements WorkflowAction {

    private final VcsClients clients;

    public PrConversationAction(VcsClients clients) {
        this.clients = clients;
    }

    @Override
    public String type() {
        return "pr.conversation";
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

        var entries = new ArrayList<Map<String, Object>>();
        var discussions = readDiscussions(vcs, owner, repo, number);
        if (discussions.isEmpty()) {
            addComments(vcs, entries, owner, repo, number);
            addReviews(vcs, entries, owner, repo, number);
        } else {
            addDiscussions(discussions, entries);
        }
        entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getOrDefault("created_at", ""))));
        return Map.of("entries", entries, "count", entries.size());
    }

    private List<DiscussionEntry> readDiscussions(VcsClient vcs, String owner, String repo, int number) {
        try {
            return vcs.getPrDiscussions(owner, repo, number);
        } catch (RuntimeException e) {
            log.warn("Could not read discussions on PR #{}", number, e);
            return List.of();
        }
    }

    private void addDiscussions(List<DiscussionEntry> discussions, List<Map<String, Object>> entries) {
        for (var discussion : discussions) {
            for (int i = 0; i < discussion.notes().size(); i++) {
                var note = discussion.notes().get(i);
                boolean positioned = note.path() != null && !note.path().isEmpty();
                var entry = entry(
                    note.userLogin(),
                    note.body(),
                    positioned ? "review_comment" : "comment",
                    String.valueOf(note.createdAt())
                );
                entry.put("discussion", discussion.id());
                entry.put("resolved", discussion.resolved());
                entry.put("path", note.path() == null ? "" : note.path());
                entry.put("line", note.line());
                entry.put("reply", i > 0);
                entries.add(entry);
            }
        }
    }

    private void addComments(VcsClient vcs, List<Map<String, Object>> entries, String owner, String repo, int number) {
        try {
            for (var comment : vcs.getPrComments(owner, repo, number)) {
                entries.add(entry(comment.userLogin(), comment.body(), "comment", String.valueOf(comment.createdAt())));
            }
        } catch (RuntimeException e) {
            log.warn("Could not read comments on PR #{}", number, e);
        }
    }

    private void addReviews(VcsClient vcs, List<Map<String, Object>> entries, String owner, String repo, int number) {
        try {
            for (var review : vcs.getPrReviews(owner, repo, number)) {
                String commitId = review.commitId() == null ? "" : review.commitId();
                if (review.body() != null && !review.body().isBlank()) {
                    var entry = entry(
                        review.userLogin(),
                        review.body(),
                        "review",
                        String.valueOf(review.submittedAt())
                    );
                    entry.put("commit_id", commitId);
                    entries.add(entry);
                }
                if (review.id() <= 0) continue;
                try {
                    for (var inline : vcs.getReviewComments(owner, repo, number, review.id())) {
                        var entry = entry(
                            inline.userLogin(),
                            inline.body(),
                            "review_comment",
                            String.valueOf(inline.createdAt())
                        );
                        entry.put("path", inline.path() == null ? "" : inline.path());
                        entry.put("line", inline.position());
                        entry.put("commit_id", commitId);
                        entries.add(entry);
                    }
                } catch (RuntimeException e) {
                    log.warn("Could not read review {} on PR #{}", review.id(), number, e);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not read reviews on PR #{}", number, e);
        }
    }

    private static LinkedHashMap<String, Object> entry(String user, String body, String type, String createdAt) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("user", user);
        entry.put("body", body == null ? "" : body);
        entry.put("type", type);
        entry.put("created_at", createdAt);
        return entry;
    }
}
