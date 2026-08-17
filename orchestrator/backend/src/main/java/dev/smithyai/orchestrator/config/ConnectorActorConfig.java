package dev.smithyai.orchestrator.config;

public record ConnectorActorConfig(
    String username,
    String accountId,
    String email,
    SecretRef token,
    SecretRef apiToken,
    GitIdentity git
) {
    public record GitIdentity(String name, String email) {}

    public String resolvedUsername(String actor) {
        return username != null && !username.isBlank() ? username : actor;
    }

    public String resolvedAccountId(String actor) {
        return accountId != null && !accountId.isBlank() ? accountId : resolvedUsername(actor);
    }

    public String resolvedGitName(String actor) {
        return git != null && git.name() != null && !git.name().isBlank()
            ? git.name()
            : Character.toUpperCase(actor.charAt(0)) + actor.substring(1);
    }

    public String resolvedGitEmail(String actor) {
        if (git != null && git.email() != null && !git.email().isBlank()) return git.email();
        if (email != null && !email.isBlank()) return email;
        return actor + "@localhost";
    }
}
