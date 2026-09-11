package dev.smithyai.knowledgebase.config;

import java.util.List;

public record KnowledgebaseConfig(
    ServerConfig server,
    VcsConfig vcs,
    VectorstoreConfig vectorstore,
    int chunkSize,
    WebhookConfig webhook,
    String aiProvider,
    OllamaConfig ollama,
    OpenaiConfig openai,
    List<RepositoryConfig> repositories
) {
    public record ServerConfig(int port) {}

    public record VcsConfig(String url, String token) {}

    public record VectorstoreConfig(String path) {}

    public record WebhookConfig(String secret) {
        public boolean hasSecret() {
            return secret != null && !secret.isBlank();
        }
    }

    public record OllamaConfig(String baseUrl, String embeddingModel, String chatModel) {}

    public record OpenaiConfig(String apiKey, String embeddingModel, String chatModel) {}

    public boolean isOllama() {
        return !"openai".equalsIgnoreCase(aiProvider);
    }

    public record RepositoryConfig(String name, String cloneUrl, String branch) {
        /** Filesystem/collection-safe key: "owner/repo" → "owner--repo" */
        public String safeKey() {
            return name.replace("/", "--");
        }
    }

    public RepositoryConfig findRepository(String fullName) {
        if (repositories == null) return null;
        return repositories.stream().filter(r -> r.name().equals(fullName)).findFirst().orElse(null);
    }
}
