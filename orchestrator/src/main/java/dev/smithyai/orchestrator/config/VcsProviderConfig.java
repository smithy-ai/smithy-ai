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

    public String gitAuthUser() {
        return switch (resolvedProvider()) {
            case "gitlab" -> "oauth2";
            default -> "token";
        };
    }

    public boolean hasArchitect() {
        String token = architectToken();
        return token != null && !token.isBlank();
    }

    public void validate() {
        validateProvider(resolvedProvider(), "vcs.provider");
        String issueP = resolvedIssueProvider();
        if (!issueP.equals(resolvedProvider())) {
            validateProvider(issueP, "vcs.issue-provider");
        }
    }

    private void validateProvider(String providerName, String configKey) {
        switch (providerName) {
            case "gitlab" -> {
                if (gitlab == null) {
                    throw new IllegalStateException(
                        configKey + " is 'gitlab' but vcs.gitlab section is missing in orchestrator.yml"
                    );
                }
                requireNonBlank(gitlab.url(), "vcs.gitlab.url");
                requireNonBlank(gitlab.smithyToken(), "vcs.gitlab.smithy-token");
                if (hasArchitect()) {
                    requireNonBlank(gitlab.architectToken(), "vcs.gitlab.architect-token");
                }
            }
            case "forgejo" -> {
                if (forgejo == null) {
                    throw new IllegalStateException(
                        configKey + " is 'forgejo' but vcs.forgejo section is missing in orchestrator.yml"
                    );
                }
                requireNonBlank(forgejo.url(), "vcs.forgejo.url");
                requireNonBlank(forgejo.smithyToken(), "vcs.forgejo.smithy-token");
                if (hasArchitect()) {
                    requireNonBlank(forgejo.architectToken(), "vcs.forgejo.architect-token");
                }
            }
            default -> throw new IllegalStateException(
                configKey + " is '" + providerName + "' but only 'forgejo' and 'gitlab' are supported"
            );
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " is required but missing or blank in orchestrator.yml");
        }
    }
}
