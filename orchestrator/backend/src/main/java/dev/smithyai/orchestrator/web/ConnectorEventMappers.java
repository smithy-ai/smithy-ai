package dev.smithyai.orchestrator.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.smithyai.orchestrator.config.ConnectorRegistry;
import dev.smithyai.orchestrator.config.WorkflowPolicyConfig;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Builds one inbound event adapter per named connector. */
@Component
public class ConnectorEventMappers {

    private final ConnectorRegistry connectors;
    private final WorkflowPolicyConfig workflowPolicy;
    private final Map<String, Object> mappers = new ConcurrentHashMap<>();

    public ConnectorEventMappers(ConnectorRegistry connectors, WorkflowPolicyConfig workflowPolicy) {
        this.connectors = connectors;
        this.workflowPolicy = workflowPolicy;
    }

    public WorkflowEvent map(String connectorId, String eventType, JsonNode payload) {
        return switch (connectors.provider(connectorId)) {
            case "forgejo" -> mapForgejo((EventMapper) mapper(connectorId), eventType, payload);
            case "gitlab" -> ((GitLabEventMapper) mapper(connectorId)).map(eventType, payload);
            case "github" -> ((GitHubEventMapper) mapper(connectorId)).map(eventType, payload);
            case "jira" -> ((JiraEventMapper) mapper(connectorId)).map(payload);
            default -> throw new IllegalArgumentException("Unsupported connector provider");
        };
    }

    private Object mapper(String connectorId) {
        return mappers.computeIfAbsent(connectorId, this::create);
    }

    private Object create(String connectorId) {
        String provider = connectors.provider(connectorId);
        String vcsConnector = "jira".equals(provider) ? connectors.defaultVcs() : connectorId;
        var providerConfig = connectors.providerConfig(vcsConnector, connectorId);
        var bots = connectors.botConfig(connectorId);
        var vcs = connectors.vcs(vcsConnector, connectors.defaultActor());
        return switch (provider) {
            case "forgejo" -> new EventMapper(bots, providerConfig, workflowPolicy, vcs, connectorId);
            case "gitlab" -> new GitLabEventMapper(bots, providerConfig, workflowPolicy, vcs, connectorId);
            case "github" -> new GitHubEventMapper(bots, providerConfig, workflowPolicy, vcs, connectorId);
            case "jira" -> new JiraEventMapper(
                providerConfig,
                vcs,
                connectors.issues(connectorId, connectors.defaultActor()),
                connectorId
            );
            default -> throw new IllegalArgumentException("Unsupported connector provider " + provider);
        };
    }

    private static WorkflowEvent mapForgejo(EventMapper mapper, String eventType, JsonNode payload) {
        String action = payload.path("action").asText(null);
        return switch (eventType) {
            case "issues" -> mapper.mapIssueEvent(action, payload);
            case "issue_comment" -> mapper.mapIssueComment(payload);
            case "push" -> mapper.mapPush(payload);
            case "pull_request" -> mapper.mapPullRequest(action, payload);
            case "pull_request_comment" -> "reviewed".equals(action)
                ? mapper.mapReviewSubmitted(payload)
                : mapper.mapPrComment(payload);
            case "pull_request_rejected" -> mapper.mapReviewSubmitted(payload);
            case "action_run_failure", "action_run_recover" -> mapper.mapCiEvent(eventType, payload);
            default -> null;
        };
    }
}
