package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Post a comment on an issue.
 *
 * <p>The first real action, and the shape the rest follow: declare the type a
 * step names, declare what the provider must support, do one thing, and return
 * outputs the next step can address.
 */
@Component
public class IssueCommentAction implements WorkflowAction {

    private final IssueTrackerClient issues;

    public IssueCommentAction(@Qualifier("smithyIssueTracker") IssueTrackerClient issues) {
        this.issues = issues;
    }

    @Override
    public String type() {
        return "issue.comment";
    }

    @Override
    public Set<Capability> requires() {
        return Set.of(Capability.ISSUE_COMMENT);
    }

    @Override
    public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
        String owner = required(input, "owner");
        String repo = required(input, "repo");
        String issueRef = required(input, "issue");
        String body = required(input, "body");

        var comment = issues.createIssueComment(owner, repo, issueRef, body);
        return Map.of("commentId", comment.id());
    }

    private static String required(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("issue.comment requires '" + key + "'");
        }
        return String.valueOf(value);
    }
}
