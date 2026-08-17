package dev.smithyai.orchestrator.config;

import java.util.Set;
import org.springframework.core.env.Environment;

final class OrchestratorConfigValidator {

    private static final Set<String> PROVIDERS = Set.of("forgejo", "gitlab", "github", "jira");

    private OrchestratorConfigValidator() {}

    static void validate(OrchestratorConfig config, Environment environment) {
        require(
            OrchestratorConfig.API_VERSION.equals(config.apiVersion()),
            "apiVersion must be " + OrchestratorConfig.API_VERSION
        );
        require(OrchestratorConfig.KIND.equals(config.kind()), "kind must be " + OrchestratorConfig.KIND);
        require(!config.connectors().isEmpty(), "connectors must contain at least one connector");
        var defaults = config.defaults();
        require(defaults != null, "defaults is required");
        require(config.connectors().containsKey(defaults.vcs()), "defaults.vcs must name a configured connector");
        require(
            !"jira".equals(config.connectors().get(defaults.vcs()).resolvedProvider()),
            "defaults.vcs cannot name a Jira connector"
        );
        if (!DefaultsConfig.EVENT_SOURCE.equals(defaults.resolvedIssueTracker())) {
            require(
                config.connectors().containsKey(defaults.resolvedIssueTracker()),
                "defaults.issueTracker must be event.source or name a configured connector"
            );
        }

        config.connectors().forEach((id, connector) -> validateConnector(id, connector, defaults, environment));
        config
            .repositoryCatalogs()
            .forEach((name, entries) -> {
                require(name != null && !name.isBlank(), "repository catalog names cannot be blank");
                entries.forEach(entry -> {
                    require(
                        config.connectors().containsKey(entry.source()),
                        "catalog " + name + " names unknown source " + entry.source()
                    );
                    require(
                        !"jira".equals(config.connectors().get(entry.source()).resolvedProvider()),
                        "catalog " + name + " source " + entry.source() + " is not a VCS connector"
                    );
                    require(
                        entry.owner() != null && !entry.owner().isBlank(),
                        "catalog " + name + " entry owner is required"
                    );
                    require(
                        entry.repo() != null && !entry.repo().isBlank(),
                        "catalog " + name + " entry repo is required"
                    );
                });
            });

        var claude = config.agent() == null ? null : config.agent().claude();
        require(claude != null, "agent.claude is required");
        String oauth = resolve(claude.oauthToken(), environment, "agent.claude.oauthToken");
        String apiKey = resolve(claude.apiKey(), environment, "agent.claude.apiKey");
        require(!oauth.isBlank() || !apiKey.isBlank(), "agent.claude requires oauthToken or apiKey");
    }

    private static void validateConnector(
        String id,
        ConnectorConfig connector,
        DefaultsConfig defaults,
        Environment environment
    ) {
        require(id != null && id.matches("[a-z0-9][a-z0-9._-]*"), "invalid connector id: " + id);
        String provider = connector.resolvedProvider();
        require(PROVIDERS.contains(provider), "connector " + id + " has unsupported provider " + provider);
        require(connector.url() != null && !connector.url().isBlank(), "connectors." + id + ".url is required");
        require(!connector.actors().isEmpty(), "connectors." + id + ".actors must not be empty");
        require(
            connector.actors().containsKey(defaults.resolvedActor()),
            "connectors." + id + ".actors must define the default actor " + defaults.resolvedActor()
        );
        resolve(connector.webhookSecret(), environment, "connectors." + id + ".webhookSecret");
        connector
            .actors()
            .forEach((actor, identity) -> {
                require(actor != null && !actor.isBlank(), "connectors." + id + " has a blank actor name");
                if ("jira".equals(provider)) {
                    require(
                        identity.accountId() != null && !identity.accountId().isBlank(),
                        "connectors." + id + ".actors." + actor + ".accountId is required"
                    );
                    require(
                        !resolve(
                            identity.apiToken(),
                            environment,
                            "connectors." + id + ".actors." + actor + ".apiToken"
                        ).isBlank(),
                        "connectors." + id + ".actors." + actor + ".apiToken is required"
                    );
                } else {
                    require(
                        !resolve(
                            identity.token(),
                            environment,
                            "connectors." + id + ".actors." + actor + ".token"
                        ).isBlank(),
                        "connectors." + id + ".actors." + actor + ".token is required"
                    );
                }
            });
    }

    private static String resolve(SecretRef ref, Environment environment, String field) {
        return ref == null ? "" : ref.resolve(environment, field);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message + " in orchestrator.yml");
    }
}
