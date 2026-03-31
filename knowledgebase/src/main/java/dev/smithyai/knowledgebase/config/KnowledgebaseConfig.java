package dev.smithyai.knowledgebase.config;

public record KnowledgebaseConfig(
    ServerConfig server,
    GitConfig git,
    VectorstoreConfig vectorstore,
    int chunkSize,
    WebhookConfig webhook,
    OpenaiConfig openai
) {
    public record ServerConfig(int port) {}

    public record GitConfig(String repositoryUrl, String branch, String accessToken, String localPath) {
        public boolean isConfigured() {
            return repositoryUrl != null && !repositoryUrl.isBlank();
        }
    }

    public record VectorstoreConfig(String path) {}

    public record WebhookConfig(String secret) {
        public boolean hasSecret() {
            return secret != null && !secret.isBlank();
        }
    }

    public record OpenaiConfig(String apiKey, String embeddingModel, String chatModel) {}
}
