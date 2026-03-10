package dev.smithyai.orchestrator.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record VcsProviderConfig(
    String provider,
    @JsonProperty("issue-provider") String issueProvider,
    ForgejoProviderConfig forgejo,
    GitLabProviderConfig gitlab
) {
    public record ForgejoProviderConfig(
        String url,
        @JsonProperty("external-url") String externalUrl,
        @JsonProperty("webhook-secret") String webhookSecret,
        @JsonProperty("smithy-token") String smithyToken,
        @JsonProperty("architect-token") String architectToken
    ) {}

    public record GitLabProviderConfig(
        String url,
        @JsonProperty("external-url") String externalUrl,
        @JsonProperty("webhook-secret") String webhookSecret,
        @JsonProperty("smithy-token") String smithyToken,
        @JsonProperty("architect-token") String architectToken
    ) {}

    public String resolvedProvider() {
        return provider != null && !provider.isBlank() ? provider : "forgejo";
    }

    public String resolvedIssueProvider() {
        return issueProvider != null && !issueProvider.isBlank() ? issueProvider : resolvedProvider();
    }

    public String resolvedUrl() {
        return switch (resolvedProvider()) {
            case "gitlab" -> gitlab != null ? gitlab.url() : null;
            default -> forgejo != null ? forgejo.url() : null;
        };
    }

    public String resolvedExternalUrl() {
        return switch (resolvedProvider()) {
            case "gitlab" -> {
                if (gitlab == null) yield null;
                yield gitlab.externalUrl() != null && !gitlab.externalUrl().isBlank()
                    ? gitlab.externalUrl()
                    : gitlab.url();
            }
            default -> forgejo != null ? forgejo.externalUrl() : null;
        };
    }

    public String smithyToken() {
        return switch (resolvedProvider()) {
            case "gitlab" -> gitlab != null ? gitlab.smithyToken() : null;
            default -> forgejo != null ? forgejo.smithyToken() : null;
        };
    }

    public String architectToken() {
        return switch (resolvedProvider()) {
            case "gitlab" -> gitlab != null ? gitlab.architectToken() : null;
            default -> forgejo != null ? forgejo.architectToken() : null;
        };
    }

    public boolean hasArchitect() {
        String token = architectToken();
        return token != null && !token.isBlank();
    }
}
