package dev.smithyai.orchestrator.config;

import java.util.LinkedHashMap;
import java.util.Map;

public record ConnectorConfig(
    String provider,
    String url,
    String externalUrl,
    SecretRef webhookSecret,
    Map<String, ConnectorActorConfig> actors,
    String tokenType,
    IssueMappingConfig issueMapping
) {
    public ConnectorConfig {
        actors = actors == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(actors));
    }

    public record IssueMappingConfig(
        String repositoryField,
        Boolean allowStoriesWithoutRepository,
        String planApprovedLabel,
        String planApprovedStatus
    ) {
        public boolean allowsStoriesWithoutRepository() {
            return Boolean.TRUE.equals(allowStoriesWithoutRepository);
        }
    }

    public String resolvedProvider() {
        return provider == null ? "" : provider.strip().toLowerCase(java.util.Locale.ROOT);
    }

    public String resolvedExternalUrl() {
        return externalUrl != null && !externalUrl.isBlank() ? externalUrl : url;
    }

    public boolean isOAuth2() {
        return tokenType == null || tokenType.isBlank() || "oauth2".equalsIgnoreCase(tokenType);
    }
}
