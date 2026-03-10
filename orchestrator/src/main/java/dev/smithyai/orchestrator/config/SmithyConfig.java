package dev.smithyai.orchestrator.config;

public record SmithyConfig(DockerConfig docker, ClaudeConfig claude, VcsProviderConfig vcs, BotConfig bots) {}
