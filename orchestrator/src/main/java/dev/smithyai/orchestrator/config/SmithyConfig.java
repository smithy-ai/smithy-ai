package dev.smithyai.orchestrator.config;

public record SmithyConfig(
    DockerConfig docker,
    ClaudeConfig claude,
    CodexConfig codex,
    VcsProviderConfig vcs,
    BotConfig bots,
    KnowledgebaseConfig knowledgebase
) {}
