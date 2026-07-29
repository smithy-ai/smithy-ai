package dev.smithyai.orchestrator.model;

/**
 * Issue identity is a provider-native string reference: a plain number for
 * GitLab/GitHub/Forgejo ("123") or a key for Jira ("ECD-4309").
 */
public record IssueContext(RepoInfo info, String issueRef, String title, String body, String baseBranch) {}
