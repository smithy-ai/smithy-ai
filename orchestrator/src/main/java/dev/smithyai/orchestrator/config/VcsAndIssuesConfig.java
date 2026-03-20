package dev.smithyai.orchestrator.config;

import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.forgejo.ForgejoClient;
import dev.smithyai.orchestrator.service.vcs.gitlab.GitLabClient;
import dev.smithyai.orchestrator.web.GitLabEventMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

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
        @Qualifier("smithyVcs") VcsClient smithyVcs
    ) {
        if (!"gitlab".equals(vcs.resolvedProvider())) {
            return null;
        }
        return new GitLabEventMapper(botConfig, vcs, smithyVcs);
    }

    private VcsClient createVcsClient(VcsProviderConfig vcs, String provider, boolean architect) {
        return switch (provider) {
            case "gitlab" -> {
                var gl = vcs.gitlab();
                String token = architect ? gl.architectToken() : gl.smithyToken();
                yield new GitLabClient(gl.url(), gl.externalUrl(), token);
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
            case "gitlab" -> {
                var gl = vcs.gitlab();
                String token = architect ? gl.architectToken() : gl.smithyToken();
                yield new GitLabClient(gl.url(), gl.externalUrl(), token);
            }
            default -> {
                var fg = vcs.forgejo();
                String token = architect ? fg.architectToken() : fg.smithyToken();
                yield new ForgejoClient(fg.url(), token);
            }
        };
    }
}
