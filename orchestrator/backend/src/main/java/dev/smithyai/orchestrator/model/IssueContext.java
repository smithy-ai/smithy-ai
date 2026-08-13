package dev.smithyai.orchestrator.model;

/**
 * Issue identity is a provider-native string reference: a plain number for
 * GitLab/GitHub/Forgejo ("123") or a key for Jira ("ECD-4309").
 *
 * @param assignee which of this orchestrator's actors the issue was handed to.
 *                 How a person says what kind of work it is — a feature to the
 *                 coordinator, a task to smithy — and therefore which workflow
 *                 should pick it up. Empty where the provider did not say.
 */
public record IssueContext(
    RepoInfo info,
    String issueRef,
    String title,
    String body,
    String baseBranch,
    String assignee
) {
    public IssueContext(RepoInfo info, String issueRef, String title, String body, String baseBranch) {
        this(info, issueRef, title, body, baseBranch, "");
    }
}
