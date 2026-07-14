package dev.smithyai.orchestrator.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ClaudeConfig(@JsonProperty("oauth-token") String oauthToken, @JsonProperty("api-key") String apiKey) {
    public boolean hasOauthToken() {
        return oauthToken != null && !oauthToken.isBlank();
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public void validate() {
        if (!hasOauthToken() && !hasApiKey()) {
            throw new IllegalStateException(
                "Either claude.oauth-token (CLAUDE_CODE_OAUTH_TOKEN) or claude.api-key (ANTHROPIC_API_KEY) " +
                "is required but both are missing or blank in orchestrator.yml"
            );
        }
    }
}
