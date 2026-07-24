package dev.smithyai.orchestrator.config;

public record SmithyConfig(
    RuntimeConfig runtime,
    DockerConfig docker,
    ClaudeConfig claude,
    VcsProviderConfig vcs,
    BotConfig bots,
    KnowledgebaseConfig knowledgebase
) {}
