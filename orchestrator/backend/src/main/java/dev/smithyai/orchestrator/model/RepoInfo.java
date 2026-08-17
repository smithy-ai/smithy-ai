package dev.smithyai.orchestrator.model;

/**
 * Where an event happened.
 *
 * @param source the stable connector id the event arrived through
 * @param sourceProvider the connector implementation: {@code forgejo},
 *               {@code gitlab}, {@code github}, or {@code jira}. Event origin
 *               rather than repository identity: a story tracked in Jira has no
 *               repository behind it at all, and owner/repo then name the
 *               project it lives in. Carried here because every event has one of
 *               these; read it as {@code event.source()}.
 */
public record RepoInfo(String owner, String repo, String cloneUrl, String source, String sourceProvider) {
    /** Connector names, as an operator writes them in configuration. */
    public static final String FORGEJO = "forgejo";
    public static final String GITLAB = "gitlab";
    public static final String GITHUB = "github";
    public static final String JIRA = "jira";

    /** Where the connector is not known — internal events, and older tests. */
    public RepoInfo(String owner, String repo, String cloneUrl) {
        this(owner, repo, cloneUrl, "", "");
    }

    public RepoInfo(String owner, String repo, String cloneUrl, String source) {
        this(owner, repo, cloneUrl, source, providerName(source));
    }

    public String fullName() {
        return owner + "/" + repo;
    }

    private static String providerName(String source) {
        return java.util.Set.of(FORGEJO, GITLAB, GITHUB, JIRA).contains(source) ? source : "";
    }
}
