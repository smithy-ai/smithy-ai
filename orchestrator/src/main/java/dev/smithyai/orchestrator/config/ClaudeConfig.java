package dev.smithyai.orchestrator.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ClaudeConfig(@JsonProperty("oauth-token") String oauthToken) {}
