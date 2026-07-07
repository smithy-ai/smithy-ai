package dev.smithyai.orchestrator.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CodexConfig(boolean enabled, @JsonProperty("api-key") String apiKey, String model) {
    public static CodexConfig disabled() {
        return new CodexConfig(false, null, null);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean hasModel() {
        return model != null && !model.isBlank();
    }

    public void validate() {
        if (enabled && !hasApiKey()) {
            throw new IllegalStateException(
                "codex.api-key (OPENAI_API_KEY) is required when codex.enabled is true in orchestrator.yml"
            );
        }
    }
}
