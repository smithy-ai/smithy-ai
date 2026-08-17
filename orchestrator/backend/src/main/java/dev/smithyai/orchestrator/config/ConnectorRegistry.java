package dev.smithyai.orchestrator.config;

import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.forgejo.ForgejoClient;
import dev.smithyai.orchestrator.service.vcs.github.GitHubClient;
import dev.smithyai.orchestrator.service.vcs.gitlab.GitLabClient;
import dev.smithyai.orchestrator.service.vcs.jira.JiraClient;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Named provider connections and their connector-local machine identities. */
@Component
public class ConnectorRegistry {

    private final OrchestratorConfig config;
    private final Environment environment;
    private final Map<ClientKey, VcsClient> vcsClients = new ConcurrentHashMap<>();
    private final Map<ClientKey, IssueTrackerClient> issueClients = new ConcurrentHashMap<>();

    public ConnectorRegistry(OrchestratorConfig config, Environment environment) {
        this.config = config;
        this.environment = environment;
    }

    public Set<String> connectorIds() {
        return config.connectors().keySet();
    }

    public Set<String> vcsConnectorIds() {
        var ids = new LinkedHashSet<String>();
        config
            .connectors()
            .forEach((id, connector) -> {
                if (!"jira".equals(connector.resolvedProvider())) ids.add(id);
            });
        return Set.copyOf(ids);
    }

    public Set<String> connectorIds(String provider) {
        var ids = new LinkedHashSet<String>();
        config
            .connectors()
            .forEach((id, connector) -> {
                if (provider.equals(connector.resolvedProvider())) ids.add(id);
            });
        return Set.copyOf(ids);
    }

    public Set<String> actors() {
        var actors = new LinkedHashSet<String>();
        config
            .connectors()
            .values()
            .forEach(connector -> actors.addAll(connector.actors().keySet()));
        actors.add(config.defaults().resolvedActor());
        return Set.copyOf(actors);
    }

    public String defaultVcs() {
        return config.defaults().vcs();
    }

    public String defaultActor() {
        return config.defaults().resolvedActor();
    }

    public String defaultIssueTracker(String eventSource) {
        String configured = config.defaults().resolvedIssueTracker();
        if (DefaultsConfig.EVENT_SOURCE.equals(configured)) {
            return eventSource == null || eventSource.isBlank() ? defaultVcs() : eventSource;
        }
        return configured;
    }

    public ConnectorConfig connector(String connectorId) {
        ConnectorConfig connector = config.connectors().get(connectorId);
        if (connector == null) {
            throw new IllegalArgumentException(
                "No connector named '%s' is configured; available: %s".formatted(connectorId, connectorIds())
            );
        }
        return connector;
    }

    public String provider(String connectorId) {
        return connector(connectorId).resolvedProvider();
    }

    public ConnectorActorConfig actor(String connectorId, String actorName) {
        ConnectorConfig connector = connector(connectorId);
        String logicalActor = normalizeActor(actorName);
        ConnectorActorConfig identity = connector.actors().get(logicalActor);
        if (identity == null) {
            throw new IllegalArgumentException(
                "Connector '%s' has no identity for actor '%s'; configured actors: %s".formatted(
                    connectorId,
                    logicalActor,
                    connector.actors().keySet()
                )
            );
        }
        return identity;
    }

    public boolean hasActor(String connectorId, String actorName) {
        return connector(connectorId).actors().containsKey(normalizeActor(actorName));
    }

    public String username(String connectorId, String actorName) {
        String logicalActor = normalizeActor(actorName);
        return actor(connectorId, logicalActor).resolvedUsername(logicalActor);
    }

    public String accountId(String connectorId, String actorName) {
        String logicalActor = normalizeActor(actorName);
        return actor(connectorId, logicalActor).resolvedAccountId(logicalActor);
    }

    public String assignee(String connectorId, String actorName) {
        return "jira".equals(provider(connectorId))
            ? accountId(connectorId, actorName)
            : username(connectorId, actorName);
    }

    public String gitName(String connectorId, String actorName) {
        String logicalActor = normalizeActor(actorName);
        return actor(connectorId, logicalActor).resolvedGitName(logicalActor);
    }

    public String gitEmail(String connectorId, String actorName) {
        String logicalActor = normalizeActor(actorName);
        return actor(connectorId, logicalActor).resolvedGitEmail(logicalActor);
    }

    public String token(String connectorId, String actorName) {
        String logicalActor = normalizeActor(actorName);
        ConnectorActorConfig identity = actor(connectorId, logicalActor);
        SecretRef ref = "jira".equals(provider(connectorId)) ? identity.apiToken() : identity.token();
        String field = "jira".equals(provider(connectorId)) ? "apiToken" : "token";
        return resolve(ref, "connectors." + connectorId + ".actors." + logicalActor + "." + field);
    }

    public String webhookSecret(String connectorId) {
        return resolve(connector(connectorId).webhookSecret(), "connectors." + connectorId + ".webhookSecret");
    }

    public BotConfig botConfig(String connectorId) {
        return ProviderConfigAdapter.toBotConfig(config, connectorId);
    }

    public VcsProviderConfig providerConfig(String vcsConnectorId, String issueConnectorId) {
        return ProviderConfigAdapter.toVcsProviderConfig(config, environment, vcsConnectorId, issueConnectorId);
    }

    public String gitAuthUser(String connectorId) {
        ConnectorConfig connector = connector(connectorId);
        return switch (connector.resolvedProvider()) {
            case "gitlab" -> connector.isOAuth2() ? "oauth2" : "private-token";
            case "github" -> "x-access-token";
            default -> "token";
        };
    }

    public VcsClient vcs(String connectorId, String actorName) {
        String actor = normalizeActor(actorName);
        return vcsClients.computeIfAbsent(new ClientKey(connectorId, actor), key ->
            createVcs(key.connector(), key.actor())
        );
    }

    public IssueTrackerClient issues(String connectorId, String actorName) {
        String actor = normalizeActor(actorName);
        return issueClients.computeIfAbsent(new ClientKey(connectorId, actor), key ->
            createIssueTracker(key.connector(), key.actor())
        );
    }

    private VcsClient createVcs(String connectorId, String actor) {
        ConnectorConfig connector = connector(connectorId);
        String token = token(connectorId, actor);
        return switch (connector.resolvedProvider()) {
            case "forgejo" -> new ForgejoClient(connector.url(), token);
            case "gitlab" -> new GitLabClient(
                connector.url(),
                connector.resolvedExternalUrl(),
                token,
                connector.isOAuth2()
            );
            case "github" -> new GitHubClient(connector.url(), connector.resolvedExternalUrl(), token);
            case "jira" -> throw new IllegalArgumentException("Connector '" + connectorId + "' is not a VCS connector");
            default -> throw new IllegalArgumentException("Unsupported provider " + connector.provider());
        };
    }

    private IssueTrackerClient createIssueTracker(String connectorId, String actor) {
        ConnectorConfig connector = connector(connectorId);
        if (!"jira".equals(connector.resolvedProvider())) return (IssueTrackerClient) vcs(connectorId, actor);
        ConnectorActorConfig identity = actor(connectorId, actor);
        var mapping = connector.issueMapping();
        var jira = new VcsProviderConfig.JiraProviderConfig(
            connector.url(),
            identity.email(),
            token(connectorId, actor),
            identity.resolvedAccountId(actor),
            null,
            null,
            webhookSecret(connectorId),
            mapping == null ? null : mapping.repositoryField(),
            mapping == null ? null : mapping.planApprovedLabel(),
            mapping == null ? null : mapping.planApprovedStatus(),
            mapping != null && mapping.allowsStoriesWithoutRepository()
        );
        return new JiraClient(jira);
    }

    private String normalizeActor(String actor) {
        return actor == null || actor.isBlank() ? defaultActor() : actor;
    }

    private String resolve(SecretRef ref, String field) {
        return ref == null ? "" : ref.resolve(environment, field);
    }

    private record ClientKey(String connector, String actor) {}
}
