package dev.smithyai.orchestrator.runtime.actions;

/**
 * Something a provider must be able to do for an action to work.
 *
 * <p>The provider clients grew {@code default} methods that throw
 * {@code UnsupportedOperationException}, so a workflow needing one of them
 * failed at the moment it ran — typically mid-flight, on someone's issue.
 * Actions declare what they need and definitions are checked against the
 * configured provider at load time instead.
 */
public enum Capability {
    ISSUE_COMMENT("issue.comment"),
    ISSUE_CREATE("issue.create"),
    ISSUE_LABEL("issue.label"),
    ISSUE_ASSIGN("issue.assign"),
    PR_CREATE("pr.create"),
    PR_COMMENT("pr.comment"),
    PR_REVIEW_INLINE("pr.review.inline"),
    PR_REQUEST_REVIEW("pr.request_review"),
    FILE_READ("file.read"),
    FILE_DELETE("file.delete"),
    BRANCH_SEARCH("branch.search"),
    COMMENT_REACT("comment.react"),
    DISCUSSION_REPLY("discussion.reply"),
    /** An execution environment — a task container the run owns. */
    ENVIRONMENT("environment"),
    /** An agent session inside that environment. */
    AGENT("agent");

    private final String value;

    Capability(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
