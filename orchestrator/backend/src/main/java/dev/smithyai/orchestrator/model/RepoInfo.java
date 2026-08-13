package dev.smithyai.orchestrator.model;

/**
 * Where an event happened.
 *
 * @param source the connector the event arrived through — {@code forgejo},
 *               {@code gitlab}, {@code github}, {@code jira}. Event origin
 *               rather than repository identity: a story tracked in Jira has no
 *               repository behind it at all, and owner/repo then name the
 *               project it lives in. Carried here because every event has one of
 *               these; read it as {@code event.source()}.
 */
public record RepoInfo(String owner, String repo, String cloneUrl, String source) {
    /** Connector names, as an operator writes them in configuration. */
    public static final String FORGEJO = "forgejo";
    public static final String GITLAB = "gitlab";
    public static final String GITHUB = "github";
    public static final String JIRA = "jira";

    /** Where the connector is not known — internal events, and older tests. */
    public RepoInfo(String owner, String repo, String cloneUrl) {
        this(owner, repo, cloneUrl, "");
    }

    public String fullName() {
        return owner + "/" + repo;
    }
}
