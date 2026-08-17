package dev.smithyai.orchestrator.config;

import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.IssueTrackers;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.VcsClients;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@lombok.extern.slf4j.Slf4j
@Configuration
public class VcsAndIssuesConfig {

    @Bean
    @Qualifier("smithyVcs")
    public VcsClient smithyVcsClient(ConnectorRegistry connectors) {
        return connectors.vcs(connectors.defaultVcs(), connectors.defaultActor());
    }

    @Bean
    @Qualifier("smithyIssueTracker")
    public IssueTrackerClient smithyIssueTrackerClient(ConnectorRegistry connectors) {
        return connectors.issues(connectors.defaultIssueTracker(""), connectors.defaultActor());
    }

    /**
     * The tracker that holds issues belonging to repositories.
     *
     * <p>Distinct from {@code smithyIssueTracker}, which may be Jira: a parent
     * story can live in Jira while the work lives in repositories, and a
     * coordinator creating a child issue means an issue in the repository, not
     * a Jira subtask. When one system does both, this is that system.
     */
    @Bean
    @Qualifier("repoIssueTracker")
    public IssueTrackerClient repoIssueTrackerClient(ConnectorRegistry connectors) {
        return connectors.issues(connectors.defaultVcs(), connectors.defaultActor());
    }

    /**
     * Every tracker this deployment can reach, keyed by the connector it speaks.
     *
     * <p>An action targets the connector its event arrived through, so a story
     * in Jira and a child issue in GitLab are each answered in their own system
     * without a workflow having to say which is which.
     */
    @Bean
    public IssueTrackers issueTrackers(
        ConnectorRegistry connectors,
        @Qualifier("smithyIssueTracker") IssueTrackerClient smithyIssueTracker
    ) {
        var byActor = new java.util.LinkedHashMap<String, java.util.Map<String, IssueTrackerClient>>();
        for (String actor : connectors.actors()) {
            var byConnector = new java.util.LinkedHashMap<String, IssueTrackerClient>();
            for (String connector : connectors.connectorIds())
                byConnector.put(connector, connectors.issues(connector, actor));
            byActor.put(actor, byConnector);
        }
        log.info("Issue trackers: connectors={}, actors={}", connectors.connectorIds(), byActor.keySet());
        return new IssueTrackers(
            byActor,
            connectors.defaultActor(),
            connectors.defaultIssueTracker(""),
            smithyIssueTracker,
            connectors::assignee
        );
    }

    /**
     * Every identity this deployment can act through on the repository host.
     *
     * <p>A workflow declares which actor it is, and the steps that write follow
     * it: the architect's review is signed by the architect, and the plan a
     * coordinator posts is not signed by the agent that will implement it.
     */
    @Bean
    public VcsClients vcsClients(ConnectorRegistry connectors) {
        var byActor = new java.util.LinkedHashMap<String, java.util.Map<String, VcsClient>>();
        for (String actor : connectors.actors()) {
            var byConnector = new java.util.LinkedHashMap<String, VcsClient>();
            for (String connector : connectors.vcsConnectorIds())
                byConnector.put(connector, connectors.vcs(connector, actor));
            byActor.put(actor, byConnector);
        }
        log.info("VCS clients: connectors={}, actors={}", connectors.vcsConnectorIds(), byActor.keySet());
        return new VcsClients(
            byActor,
            connectors.defaultActor(),
            connectors.defaultVcs(),
            connectors::username,
            connector -> connectors.connector(connector).resolvedExternalUrl()
        );
    }

    @Bean
    @Qualifier("architectVcs")
    public VcsClient architectVcsClient(ConnectorRegistry connectors) {
        return connectors.vcs(connectors.defaultVcs(), VcsProviderConfig.ARCHITECT);
    }

    @Bean
    @Qualifier("architectIssueTracker")
    public IssueTrackerClient architectIssueTrackerClient(ConnectorRegistry connectors) {
        return connectors.issues(connectors.defaultIssueTracker(""), VcsProviderConfig.ARCHITECT);
    }
}
