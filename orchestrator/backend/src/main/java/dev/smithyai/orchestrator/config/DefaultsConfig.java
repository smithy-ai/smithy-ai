package dev.smithyai.orchestrator.config;

public record DefaultsConfig(String vcs, String issueTracker, String actor) {
    public static final String EVENT_SOURCE = "event.source";

    public String resolvedActor() {
        return actor == null || actor.isBlank() ? "smithy" : actor;
    }

    public String resolvedIssueTracker() {
        return issueTracker == null || issueTracker.isBlank() ? EVENT_SOURCE : issueTracker;
    }
}
