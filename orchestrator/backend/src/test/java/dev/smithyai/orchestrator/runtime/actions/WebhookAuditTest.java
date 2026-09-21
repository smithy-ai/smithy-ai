package dev.smithyai.orchestrator.runtime.actions;

import static org.junit.jupiter.api.Assertions.*;

import dev.smithyai.orchestrator.service.vcs.dto.WebhookInfo;
import dev.smithyai.orchestrator.testing.StubVcsClient;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Whether a repository can talk back to the orchestrator, judged from its
 * webhooks.
 *
 * <p>Observed live: 132 of 138 catalog repositories had no webhook, and every
 * comment on a bot-authored merge request in them was answered with silence.
 */
class WebhookAuditTest {

    private static final String URL = "http://orchestrator:8080/webhooks/gitlab";

    private static WebhookInfo hook(String url, String... events) {
        return new WebhookInfo(url, true, Set.of(events));
    }

    @Test
    void aRepositoryWithoutAnyWebhookGetsToldSo() {
        var vcs = new StubVcsClient();
        vcs.webhooks = List.of();

        var notice = WebhookAudit.notice(vcs, "gitlab", "acme", "app");

        assertTrue(notice.isPresent());
        assertTrue(notice.get().contains("no webhook delivering to `/webhooks/gitlab`"), notice.get());
    }

    @Test
    void aWebhookForAnotherConnectorOrSystemDoesNotCount() {
        var vcs = new StubVcsClient();
        vcs.webhooks = List.of(
            hook("https://jenkins.example/project/app", WebhookInfo.PUSH),
            hook("http://orchestrator:8080/webhooks/other-gitlab", WebhookInfo.COMMENTS, WebhookInfo.PULL_REQUESTS)
        );

        assertTrue(WebhookAudit.notice(vcs, "gitlab", "acme", "app").isPresent());
    }

    @Test
    void aWebhookThatSendsCommentsAndPullRequestsIsEnough() {
        var vcs = new StubVcsClient();
        vcs.webhooks = List.of(hook(URL, WebhookInfo.COMMENTS, WebhookInfo.PULL_REQUESTS));

        assertTrue(WebhookAudit.notice(vcs, "gitlab", "acme", "app").isEmpty());
    }

    @Test
    void aWebhookBehindAProxyPrefixOrWithATrailingSlashStillCounts() {
        var vcs = new StubVcsClient();
        vcs.webhooks = List.of(
            hook("https://tools.example/smithy/webhooks/gitlab/", WebhookInfo.COMMENTS, WebhookInfo.PULL_REQUESTS)
        );

        assertTrue(WebhookAudit.notice(vcs, "gitlab", "acme", "app").isEmpty());
    }

    @Test
    void aWebhookMissingCommentEventsNamesWhatItLacks() {
        var vcs = new StubVcsClient();
        vcs.webhooks = List.of(hook(URL, WebhookInfo.PULL_REQUESTS, WebhookInfo.PUSH));

        var notice = WebhookAudit.notice(vcs, "gitlab", "acme", "app");

        assertTrue(notice.isPresent());
        assertTrue(notice.get().contains("does not send comment events"), notice.get());
        assertTrue(notice.get().contains("comments or reviews"), notice.get());
    }

    @Test
    void aWebhookMissingPullRequestEventsNamesThatInstead() {
        var vcs = new StubVcsClient();
        vcs.webhooks = List.of(hook(URL, WebhookInfo.COMMENTS));

        var notice = WebhookAudit.notice(vcs, "gitlab", "acme", "app");

        assertTrue(notice.isPresent());
        assertTrue(notice.get().contains("does not send merge/pull request events"), notice.get());
        assertTrue(notice.get().contains("merged or closed"), notice.get());
    }

    @Test
    void eventsSplitAcrossTwoWebhooksAddUp() {
        var vcs = new StubVcsClient();
        vcs.webhooks = List.of(hook(URL, WebhookInfo.COMMENTS), hook(URL, WebhookInfo.PULL_REQUESTS));

        assertTrue(WebhookAudit.notice(vcs, "gitlab", "acme", "app").isEmpty());
    }

    @Test
    void aDisabledWebhookDeliversNothing() {
        var vcs = new StubVcsClient();
        vcs.webhooks = List.of(new WebhookInfo(URL, false, Set.of(WebhookInfo.COMMENTS, WebhookInfo.PULL_REQUESTS)));

        assertTrue(WebhookAudit.notice(vcs, "gitlab", "acme", "app").isPresent());
    }

    @Test
    void notBeingAbleToListWebhooksIsNotTheSameAsHavingNone() {
        // The default stub cannot list them, like a bot without maintainer rights.
        assertTrue(WebhookAudit.notice(new StubVcsClient(), "gitlab", "acme", "app").isEmpty());

        var failing = new StubVcsClient() {
            @Override
            public List<WebhookInfo> listWebhooks(String owner, String repo) {
                throw new RuntimeException("GitLab API error 403 on GET /projects/acme%2Fapp/hooks");
            }
        };
        assertTrue(WebhookAudit.notice(failing, "gitlab", "acme", "app").isEmpty());
    }

    @Test
    void anUnparseableUrlIsNotAMatch() {
        assertFalse(WebhookAudit.deliversTo("http://[bad", "/webhooks/gitlab"));
        assertFalse(WebhookAudit.deliversTo("", "/webhooks/gitlab"));
        assertFalse(WebhookAudit.deliversTo(null, "/webhooks/gitlab"));
    }
}
