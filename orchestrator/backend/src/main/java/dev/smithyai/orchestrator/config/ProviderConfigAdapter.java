package dev.smithyai.orchestrator.config;

import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;

/** Bridges the canonical deployment model to provider-specific runtime constructors. */
final class ProviderConfigAdapter {

    private ProviderConfigAdapter() {}

    static VcsProviderConfig toVcsProviderConfig(
        OrchestratorConfig config,
        Environment environment,
        String vcsId,
        String issueId
    ) {
        ConnectorConfig vcs = config.connectors().get(vcsId);
        ConnectorConfig issue = config.connectors().get(issueId);
        VcsProviderConfig.ForgejoProviderConfig forgejo = null;
        VcsProviderConfig.GitLabProviderConfig gitlab = null;
        VcsProviderConfig.GitHubProviderConfig github = null;
        VcsProviderConfig.JiraProviderConfig jira = null;

        for (var entry : List.of(Map.entry(vcsId, vcs), Map.entry(issueId, issue))) {
            String id = entry.getKey();
            ConnectorConfig connector = entry.getValue();
            if (connector == null) continue;
            var actorTokens = actorTokens(config, environment, id);
            switch (connector.resolvedProvider()) {
                case "forgejo" -> forgejo = new VcsProviderConfig.ForgejoProviderConfig(
                    connector.url(),
                    connector.resolvedExternalUrl(),
                    resolve(connector.webhookSecret(), environment, "connectors." + id + ".webhookSecret"),
                    actorTokens.smithy(),
                    actorTokens.architect(),
                    actorTokens.coordinator()
                );
                case "gitlab" -> gitlab = new VcsProviderConfig.GitLabProviderConfig(
                    connector.url(),
                    connector.resolvedExternalUrl(),
                    resolve(connector.webhookSecret(), environment, "connectors." + id + ".webhookSecret"),
                    actorTokens.smithy(),
                    actorTokens.architect(),
                    actorTokens.coordinator(),
                    connector.tokenType()
                );
                case "github" -> github = new VcsProviderConfig.GitHubProviderConfig(
                    connector.url(),
                    connector.resolvedExternalUrl(),
                    resolve(connector.webhookSecret(), environment, "connectors." + id + ".webhookSecret"),
                    actorTokens.smithy(),
                    actorTokens.architect(),
                    actorTokens.coordinator()
                );
                case "jira" -> jira = jiraConfig(config, environment, id, connector);
                default -> throw new IllegalStateException("Unsupported provider: " + connector.provider());
            }
        }
        return new VcsProviderConfig(vcs.resolvedProvider(), issue.resolvedProvider(), forgejo, gitlab, github, jira);
    }

    private static VcsProviderConfig.JiraProviderConfig jiraConfig(
        OrchestratorConfig config,
        Environment environment,
        String id,
        ConnectorConfig connector
    ) {
        var defaultActor = actor(config, id, config.defaults().resolvedActor());
        var mapping = connector.issueMapping();
        return new VcsProviderConfig.JiraProviderConfig(
            connector.url(),
            defaultActor.email(),
            resolve(
                defaultActor.apiToken(),
                environment,
                "connectors." + id + ".actors." + config.defaults().resolvedActor() + ".apiToken"
            ),
            accountId(connector, VcsProviderConfig.SMITHY),
            accountId(connector, VcsProviderConfig.ARCHITECT),
            accountId(connector, VcsProviderConfig.COORDINATOR),
            resolve(connector.webhookSecret(), environment, "connectors." + id + ".webhookSecret"),
            mapping == null ? null : mapping.repositoryField(),
            mapping == null ? null : mapping.planApprovedLabel(),
            mapping == null ? null : mapping.planApprovedStatus(),
            mapping != null && mapping.allowsStoriesWithoutRepository()
        );
    }

    static BotConfig toBotConfig(OrchestratorConfig config, String connectorId) {
        ConnectorConfig connector = config.connectors().get(connectorId);
        return new BotConfig(
            botEntry(connector, VcsProviderConfig.SMITHY),
            botEntry(connector, VcsProviderConfig.ARCHITECT),
            botEntry(connector, VcsProviderConfig.COORDINATOR)
        );
    }

    private static BotConfig.BotEntry botEntry(ConnectorConfig connector, String actor) {
        ConnectorActorConfig identity = connector.actors().get(actor);
        if (identity == null) return null;
        return new BotConfig.BotEntry(identity.resolvedUsername(actor), identity.resolvedGitEmail(actor));
    }

    private static ActorTokens actorTokens(OrchestratorConfig config, Environment environment, String connectorId) {
        return new ActorTokens(
            token(config, environment, connectorId, VcsProviderConfig.SMITHY),
            token(config, environment, connectorId, VcsProviderConfig.ARCHITECT),
            token(config, environment, connectorId, VcsProviderConfig.COORDINATOR)
        );
    }

    private static String token(
        OrchestratorConfig config,
        Environment environment,
        String connectorId,
        String actorName
    ) {
        ConnectorActorConfig identity = config.connectors().get(connectorId).actors().get(actorName);
        if (identity == null) return "";
        return resolve(identity.token(), environment, "connectors." + connectorId + ".actors." + actorName + ".token");
    }

    private static ConnectorActorConfig actor(OrchestratorConfig config, String connectorId, String actorName) {
        ConnectorConfig connector = config.connectors().get(connectorId);
        ConnectorActorConfig identity = connector.actors().get(actorName);
        if (identity == null) identity = connector.actors().get(config.defaults().resolvedActor());
        return identity;
    }

    private static String accountId(ConnectorConfig connector, String actor) {
        ConnectorActorConfig identity = connector.actors().get(actor);
        return identity == null ? null : identity.accountId();
    }

    private static String resolve(SecretRef ref, Environment environment, String field) {
        return ref == null ? "" : ref.resolve(environment, field);
    }

    private record ActorTokens(String smithy, String architect, String coordinator) {}
}
