package dev.smithyai.orchestrator.config;

public record AuthConfig(AdminConfig admin) {
    public record AdminConfig(SecretRef passwordHash) {}

    public static AuthConfig defaults() {
        return new AuthConfig(new AdminConfig(null));
    }
}
