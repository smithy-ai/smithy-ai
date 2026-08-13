package dev.smithyai.orchestrator.config;

import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.IssueTrackers;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.VcsClients;
import dev.smithyai.orchestrator.service.vcs.forgejo.ForgejoClient;
import dev.smithyai.orchestrator.service.vcs.github.GitHubClient;
import dev.smithyai.orchestrator.service.vcs.gitlab.GitLabClient;
import dev.smithyai.orchestrator.service.vcs.jira.JiraClient;
import dev.smithyai.orchestrator.web.GitHubEventMapper;
import dev.smithyai.orchestrator.web.GitLabEventMapper;
import dev.smithyai.orchestrator.web.JiraEventMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

@lombok.extern.slf4j.Slf4j
@Configuration
public class VcsAndIssuesConfig {

    @Bean
    @Qualifier("smithyVcs")
    public VcsClient smithyVcsClient(VcsProviderConfig vcs) {
        return createVcsClient(vcs, vcs.resolvedProvider(), VcsProviderConfig.SMITHY);
    }

    @Bean
    @Qualifier("smithyIssueTracker")
    public IssueTrackerClient smithyIssueTrackerClient(
        VcsProviderConfig vcs,
        @Qualifier("smithyVcs") VcsClient smithyVcs
    ) {
        String issueProvider = vcs.resolvedIssueProvider();
        String vcsProvider = vcs.resolvedProvider();
        if (issueProvider.equals(vcsProvider) && smithyVcs instanceof IssueTrackerClient itc) {
            return itc;
        }
        return createIssueTrackerClient(vcs, issueProvider, VcsProviderConfig.SMITHY);
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
    public IssueTrackerClient repoIssueTrackerClient(
        VcsProviderConfig vcs,
        @Qualifier("smithyVcs") VcsClient smithyVcs,
        @Qualifier("smithyIssueTracker") IssueTrackerClient smithyIssueTracker
    ) {
        if (smithyVcs instanceof IssueTrackerClient itc) return itc;
        // A VCS with no issue API of its own; the configured tracker is all
        // there is, and a definition asking for repository issues will fail
        // capability validation rather than silently write them elsewhere.
        return smithyIssueTracker;
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
        VcsProviderConfig vcs,
        @Qualifier("smithyIssueTracker") IssueTrackerClient smithyIssueTracker,
        @Qualifier("repoIssueTracker") IssueTrackerClient repoIssueTracker
    ) {
        var byActor = new java.util.LinkedHashMap<String, java.util.Map<String, IssueTrackerClient>>();
        for (String actor : java.util.List.of(
            VcsProviderConfig.SMITHY,
            VcsProviderConfig.ARCHITECT,
            VcsProviderConfig.COORDINATOR
        )) {
            var byConnector = new java.util.LinkedHashMap<String, IssueTrackerClient>();
            if (VcsProviderConfig.SMITHY.equals(actor) || !vcs.hasOwnToken(actor)) {
                // No identity of its own: everything it does is attributed to the
                // default account, which is what one-account deployments have.
                byConnector.put(vcs.resolvedIssueProvider(), smithyIssueTracker);
                byConnector.putIfAbsent(vcs.resolvedProvider(), repoIssueTracker);
            } else {
                var own = createIssueTrackerClient(vcs, vcs.resolvedProvider(), actor);
                byConnector.put(vcs.resolvedProvider(), own);
                // The story tracker may be a different system, which this actor
                // may have no account on; fall back rather than refuse.
                byConnector.putIfAbsent(vcs.resolvedIssueProvider(), smithyIssueTracker);
            }
            byActor.put(actor, byConnector);
        }
        log.info(
            "Issue trackers by actor: {}",
            byActor
                .keySet()
                .stream()
                .map(a -> a + (vcs.hasOwnToken(a) ? "*" : ""))
                .toList()
        );
        return new IssueTrackers(byActor, VcsProviderConfig.SMITHY, smithyIssueTracker);
    }

    /**
     * Every identity this deployment can act through on the repository host.
     *
     * <p>A workflow declares which actor it is, and the steps that write follow
     * it: the architect's review is signed by the architect, and the plan a
     * coordinator posts is not signed by the agent that will implement it.
     */
    @Bean
    public VcsClients vcsClients(
        VcsProviderConfig vcs,
        @Qualifier("smithyVcs") VcsClient smithyVcs,
        @Qualifier("architectVcs") VcsClient architectVcs
    ) {
        var byActor = new java.util.LinkedHashMap<String, VcsClient>();
        byActor.put(VcsProviderConfig.SMITHY, smithyVcs);
        byActor.put(VcsProviderConfig.ARCHITECT, architectVcs);
        byActor.put(
            VcsProviderConfig.COORDINATOR,
            vcs.hasOwnToken(VcsProviderConfig.COORDINATOR)
                ? createVcsClient(vcs, vcs.resolvedProvider(), VcsProviderConfig.COORDINATOR)
                : smithyVcs
        );
        log.info(
            "VCS clients by actor: {}",
            byActor
                .keySet()
                .stream()
                .map(a -> a + (vcs.hasOwnToken(a) ? "*" : ""))
                .toList()
        );
        return new VcsClients(byActor, VcsProviderConfig.SMITHY);
    }

    @Bean
    @Qualifier("architectVcs")
    public VcsClient architectVcsClient(VcsProviderConfig vcs, @Qualifier("smithyVcs") VcsClient smithyVcs) {
        if (!vcs.hasArchitect()) {
            return smithyVcs;
        }
        return createVcsClient(vcs, vcs.resolvedProvider(), VcsProviderConfig.ARCHITECT);
    }

    @Bean
    @Qualifier("architectIssueTracker")
    public IssueTrackerClient architectIssueTrackerClient(
        VcsProviderConfig vcs,
        @Qualifier("architectVcs") VcsClient architectVcs,
        @Qualifier("smithyIssueTracker") IssueTrackerClient smithyIssueTracker
    ) {
        if (!vcs.hasArchitect()) {
            return smithyIssueTracker;
        }
        String issueProvider = vcs.resolvedIssueProvider();
        String vcsProvider = vcs.resolvedProvider();
        if (issueProvider.equals(vcsProvider) && architectVcs instanceof IssueTrackerClient itc) {
            return itc;
        }
        return createIssueTrackerClient(vcs, issueProvider, VcsProviderConfig.ARCHITECT);
    }

    @Bean
    @Nullable
    public GitLabEventMapper gitLabEventMapper(
        VcsProviderConfig vcs,
        BotConfig botConfig,
        WorkflowPolicyConfig workflowPolicy,
        @Qualifier("smithyVcs") VcsClient smithyVcs
    ) {
        if (!"gitlab".equals(vcs.resolvedProvider())) {
            return null;
        }
        return new GitLabEventMapper(botConfig, vcs, workflowPolicy, smithyVcs);
    }

    @Bean
    @Nullable
    public GitHubEventMapper gitHubEventMapper(
        VcsProviderConfig vcs,
        BotConfig botConfig,
        WorkflowPolicyConfig workflowPolicy,
        @Qualifier("smithyVcs") VcsClient smithyVcs
    ) {
        if (!"github".equals(vcs.resolvedProvider())) {
            return null;
        }
        return new GitHubEventMapper(botConfig, vcs, workflowPolicy, smithyVcs);
    }

    @Bean
    @Nullable
    public JiraEventMapper jiraEventMapper(
        VcsProviderConfig vcs,
        @Qualifier("smithyVcs") VcsClient smithyVcs,
        @Qualifier("smithyIssueTracker") IssueTrackerClient smithyIssueTracker
    ) {
        if (!"jira".equals(vcs.resolvedIssueProvider())) {
            return null;
        }
        return new JiraEventMapper(vcs, smithyVcs, smithyIssueTracker);
    }

    private VcsClient createVcsClient(VcsProviderConfig vcs, String provider, String actor) {
        return switch (provider) {
            case "gitlab" -> {
                var gl = vcs.gitlab();
                String token = vcs.tokenFor(actor);
                yield new GitLabClient(gl.url(), gl.externalUrl(), token, gl.isOAuth2());
            }
            case "github" -> {
                var gh = vcs.github();
                String token = vcs.tokenFor(actor);
                yield new GitHubClient(gh.url(), gh.externalUrl(), token);
            }
            default -> {
                var fg = vcs.forgejo();
                String token = vcs.tokenFor(actor);
                yield new ForgejoClient(fg.url(), token);
            }
        };
    }

    private IssueTrackerClient createIssueTrackerClient(VcsProviderConfig vcs, String provider, String actor) {
        return switch (provider) {
            case "jira" -> new JiraClient(vcs.jira());
            case "gitlab" -> {
                var gl = vcs.gitlab();
                String token = vcs.tokenFor(actor);
                yield new GitLabClient(gl.url(), gl.externalUrl(), token, gl.isOAuth2());
            }
            case "github" -> {
                var gh = vcs.github();
                String token = vcs.tokenFor(actor);
                yield new GitHubClient(gh.url(), gh.externalUrl(), token);
            }
            default -> {
                var fg = vcs.forgejo();
                String token = vcs.tokenFor(actor);
                yield new ForgejoClient(fg.url(), token);
            }
        };
    }
}
