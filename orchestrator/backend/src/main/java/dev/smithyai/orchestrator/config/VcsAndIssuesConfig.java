package dev.smithyai.orchestrator.config;

import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.IssueTrackers;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
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
        return createVcsClient(vcs, vcs.resolvedProvider(), false);
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
        return createIssueTrackerClient(vcs, issueProvider, false);
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
        var byConnector = new java.util.LinkedHashMap<String, IssueTrackerClient>();
        byConnector.put(vcs.resolvedIssueProvider(), smithyIssueTracker);
        // The VCS's own issues, which are the same client when one system does
        // both and a different one when they are split.
        byConnector.putIfAbsent(vcs.resolvedProvider(), repoIssueTracker);
        log.info("Issue trackers available by connector: {}", byConnector.keySet());
        return new IssueTrackers(byConnector, smithyIssueTracker);
    }

    @Bean
    @Qualifier("architectVcs")
    public VcsClient architectVcsClient(VcsProviderConfig vcs, @Qualifier("smithyVcs") VcsClient smithyVcs) {
        if (!vcs.hasArchitect()) {
            return smithyVcs;
        }
        return createVcsClient(vcs, vcs.resolvedProvider(), true);
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
        return createIssueTrackerClient(vcs, issueProvider, true);
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

    private VcsClient createVcsClient(VcsProviderConfig vcs, String provider, boolean architect) {
        return switch (provider) {
            case "gitlab" -> {
                var gl = vcs.gitlab();
                String token = architect ? gl.architectToken() : gl.smithyToken();
                yield new GitLabClient(gl.url(), gl.externalUrl(), token, gl.isOAuth2());
            }
            case "github" -> {
                var gh = vcs.github();
                String token = architect ? gh.architectToken() : gh.smithyToken();
                yield new GitHubClient(gh.url(), gh.externalUrl(), token);
            }
            default -> {
                var fg = vcs.forgejo();
                String token = architect ? fg.architectToken() : fg.smithyToken();
                yield new ForgejoClient(fg.url(), token);
            }
        };
    }

    private IssueTrackerClient createIssueTrackerClient(VcsProviderConfig vcs, String provider, boolean architect) {
        return switch (provider) {
            case "jira" -> new JiraClient(vcs.jira());
            case "gitlab" -> {
                var gl = vcs.gitlab();
                String token = architect ? gl.architectToken() : gl.smithyToken();
                yield new GitLabClient(gl.url(), gl.externalUrl(), token, gl.isOAuth2());
            }
            case "github" -> {
                var gh = vcs.github();
                String token = architect ? gh.architectToken() : gh.smithyToken();
                yield new GitHubClient(gh.url(), gh.externalUrl(), token);
            }
            default -> {
                var fg = vcs.forgejo();
                String token = architect ? fg.architectToken() : fg.smithyToken();
                yield new ForgejoClient(fg.url(), token);
            }
        };
    }
}
