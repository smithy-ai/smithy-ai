package dev.smithyai.orchestrator.config;

public record AgentConfig(ClaudeAgentConfig claude) {
    public record ClaudeAgentConfig(String model, SecretRef oauthToken, SecretRef apiKey) {}
}
