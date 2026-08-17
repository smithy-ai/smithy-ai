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
                String requestedTarget = Trackers.target(this, context, input);
                String target = requestedTarget;
                List<String> actors = listInput(input, "actors");
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
