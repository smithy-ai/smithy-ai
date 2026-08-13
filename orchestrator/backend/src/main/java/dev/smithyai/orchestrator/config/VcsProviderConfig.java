package dev.smithyai.orchestrator.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record VcsProviderConfig(
    String provider,
    @JsonProperty("issue-provider") String issueProvider,
    ForgejoProviderConfig forgejo,
    GitLabProviderConfig gitlab,
    GitHubProviderConfig github,
    JiraProviderConfig jira
) {
    public record ForgejoProviderConfig(
        String url,
        @JsonProperty("external-url") String externalUrl,
        @JsonProperty("webhook-secret") String webhookSecret,
        @JsonProperty("smithy-token") String smithyToken,
        @JsonProperty("architect-token") String architectToken,
        @JsonProperty("coordinator-token") String coordinatorToken
    ) {}

    public record GitLabProviderConfig(
        String url,
        @JsonProperty("external-url") String externalUrl,
        @JsonProperty("webhook-secret") String webhookSecret,
        @JsonProperty("smithy-token") String smithyToken,
        @JsonProperty("architect-token") String architectToken,
        @JsonProperty("coordinator-token") String coordinatorToken,
        @JsonProperty("token-type") String tokenType
    ) {
        public boolean isOAuth2() {
            return tokenType == null || tokenType.isBlank() || "oauth2".equalsIgnoreCase(tokenType);
        }
    }

    public record GitHubProviderConfig(
        String url,
        @JsonProperty("external-url") String externalUrl,
        @JsonProperty("webhook-secret") String webhookSecret,
        @JsonProperty("smithy-token") String smithyToken,
        @JsonProperty("architect-token") String architectToken,
        @JsonProperty("coordinator-token") String coordinatorToken
    ) {}

    /**
     * Jira is an issue provider only (never a VCS provider). Cloud auth is
     * Basic email:api-token (email set); Server/DC is a Bearer PAT (email empty).
     */
    public record JiraProviderConfig(
        String url,
        String email,
        @JsonProperty("api-token") String apiToken,
        @JsonProperty("bot-account-id") String botAccountId,
        @JsonProperty("webhook-secret") String webhookSecret,
        @JsonProperty("repo-field") String repoField,
        @JsonProperty("plan-approved-label") String planApprovedLabel,
        @JsonProperty("plan-approved-status") String planApprovedStatus,
        @JsonProperty("stories-without-repo") Boolean storiesWithoutRepo
    ) {
        /**
         * Whether a story with no repository field is still handed to the
         * workflows. Off by default, because a development workflow needs a
         * repository; a coordinator does not, since it picks them from its
         * catalog.
         */
        public boolean allowsStoriesWithoutRepo() {
            return storiesWithoutRepo != null && storiesWithoutRepo;
        }

        public boolean isCloud() {
            return email != null && !email.isBlank();
        }

        public String resolvedPlanApprovedLabel() {
            return planApprovedLabel != null && !planApprovedLabel.isBlank() ? planApprovedLabel : "plan-approved";
        }
    }

    public String resolvedProvider() {
        return provider != null && !provider.isBlank() ? provider : "forgejo";
    }

    public String resolvedIssueProvider() {
        return issueProvider != null && !issueProvider.isBlank() ? issueProvider : resolvedProvider();
    }

    public String resolvedUrl() {
        return switch (resolvedProvider()) {
            case "gitlab" -> gitlab != null ? gitlab.url() : null;
            case "github" -> {
                if (github == null || github.url() == null || github.url().isBlank()) yield "https://github.com";
                yield github.url();
            }
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
            case "github" -> {
                if (github == null) yield "https://github.com";
                if (github.externalUrl() != null && !github.externalUrl().isBlank()) yield github.externalUrl();
                if (github.url() != null && !github.url().isBlank()) yield github.url();
                yield "https://github.com";
            }
            default -> forgejo != null ? forgejo.externalUrl() : null;
        };
    }

    /** Actor names, as a workflow refers to them. */
    public static final String SMITHY = "smithy";
    public static final String ARCHITECT = "architect";
    public static final String COORDINATOR = "coordinator";

    /**
     * The token an actor authenticates with.
     *
     * <p>Falls back to smithy's where an actor has none configured, so a
     * deployment that has not split its identities keeps working — at the cost
     * of everything being attributed to one account, which is what having
     * separate actors is meant to avoid.
     */
    public String tokenFor(String actor) {
        String token = switch (resolvedProvider()) {
            case "gitlab" -> gitlab == null
                ? null
                : switch (actor) {
                      case ARCHITECT -> gitlab.architectToken();
                      case COORDINATOR -> gitlab.coordinatorToken();
                      default -> gitlab.smithyToken();
                  };
            case "github" -> github == null
                ? null
                : switch (actor) {
                      case ARCHITECT -> github.architectToken();
                      case COORDINATOR -> github.coordinatorToken();
                      default -> github.smithyToken();
                  };
            default -> forgejo == null
                ? null
                : switch (actor) {
                      case ARCHITECT -> forgejo.architectToken();
                      case COORDINATOR -> forgejo.coordinatorToken();
                      default -> forgejo.smithyToken();
                  };
        };
        return token != null && !token.isBlank() ? token : smithyToken();
    }

    /** Whether this actor has an identity of its own here. */
    public boolean hasOwnToken(String actor) {
        String own = switch (resolvedProvider()) {
            case "gitlab" -> gitlab == null ? null : (COORDINATOR.equals(actor) ? gitlab.coordinatorToken() : null);
            case "github" -> github == null ? null : (COORDINATOR.equals(actor) ? github.coordinatorToken() : null);
            default -> forgejo == null ? null : (COORDINATOR.equals(actor) ? forgejo.coordinatorToken() : null);
        };
        return own != null && !own.isBlank();
    }

    public String smithyToken() {
        return switch (resolvedProvider()) {
            case "gitlab" -> gitlab != null ? gitlab.smithyToken() : null;
            case "github" -> github != null ? github.smithyToken() : null;
            default -> forgejo != null ? forgejo.smithyToken() : null;
        };
    }

    public String architectToken() {
        return switch (resolvedProvider()) {
            case "gitlab" -> gitlab != null ? gitlab.architectToken() : null;
            case "github" -> github != null ? github.architectToken() : null;
            default -> forgejo != null ? forgejo.architectToken() : null;
        };
    }

    public String gitAuthUser() {
        return switch (resolvedProvider()) {
            case "gitlab" -> gitlab != null && !gitlab.isOAuth2() ? "private-token" : "oauth2";
            case "github" -> "x-access-token";
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
        if ("jira".equals(resolvedProvider())) {
            throw new IllegalStateException("vcs.provider cannot be 'jira' — Jira is an issue provider only");
        }
        if (!issueP.equals(resolvedProvider())) {
            if ("jira".equals(issueP)) {
                validateJira();
            } else {
                validateProvider(issueP, "vcs.issue-provider");
            }
        }
    }

    private void validateJira() {
        if (jira == null) {
            throw new IllegalStateException(
                "vcs.issue-provider is 'jira' but vcs.jira section is missing in orchestrator.yml"
            );
        }
        requireNonBlank(jira.url(), "vcs.jira.url");
        requireNonBlank(jira.apiToken(), "vcs.jira.api-token");
        requireNonBlank(jira.botAccountId(), "vcs.jira.bot-account-id");
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
            case "github" -> {
                if (github == null) {
                    throw new IllegalStateException(
                        configKey + " is 'github' but vcs.github section is missing in orchestrator.yml"
                    );
                }
                requireNonBlank(github.smithyToken(), "vcs.github.smithy-token");
                if (hasArchitect()) {
                    requireNonBlank(github.architectToken(), "vcs.github.architect-token");
                }
            }
            default -> throw new IllegalStateException(
                configKey + " is '" + providerName + "' but only 'forgejo', 'gitlab', and 'github' are supported"
            );
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " is required but missing or blank in orchestrator.yml");
        }
    }
}
