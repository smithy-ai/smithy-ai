package dev.smithyai.orchestrator.config;

public record DefaultsConfig(String vcs, String issueTracker, String actor) {
    public static final String EVENT_SOURCE = "event.source";
    public static final String DEFAULT_ACTOR = "smithy";

    public String resolvedActor() {
        return actor == null || actor.isBlank() ? DEFAULT_ACTOR : actor;
    }

    public String resolvedIssueTracker() {
        return issueTracker == null || issueTracker.isBlank() ? EVENT_SOURCE : issueTracker;
    }
}
