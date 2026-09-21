package dev.smithyai.orchestrator.runtime.actions;

import static org.junit.jupiter.api.Assertions.*;

import dev.smithyai.orchestrator.service.vcs.dto.WebhookInfo;
import dev.smithyai.orchestrator.testing.StubVcsClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Opening a pull request in a repository that cannot report back leaves a
 * note on it saying so.
 */
class PrCreateWebhookNoticeTest {

    private static final Map<String, Object> INPUT = Map.of(
        "owner",
        "acme",
        "repo",
        "app",
        "title",
        "Add a thing",
        "head",
        "smithy/7-add-a-thing",
        "base",
        "main"
    );

    private static Map<String, Object> create(StubVcsClient vcs) {
        var action = new PullRequestActions().prCreateAction(vcs.asRegistry());
        return action.execute(new ActionContext(null, null, Map.of(), Map.of()), INPUT);
    }

    @Test
    void aRepositoryWithoutAWebhookGetsANoticeOnTheNewPullRequest() {
        var vcs = new StubVcsClient();
        vcs.webhooks = List.of();

        var output = create(vcs);

        assertEquals(true, output.get("webhookMissing"));
        assertEquals(1, vcs.postedPrComments.size());
        var posted = vcs.postedPrComments.getFirst();
        assertEquals(vcs.createdPrs.getFirst().number(), posted.number());
        assertTrue(posted.body().contains("/webhooks/default"), posted.body());
    }

    @Test
    void aRepositoryThatReportsBackGetsNoNotice() {
        var vcs = new StubVcsClient();
        vcs.webhooks = List.of(
            new WebhookInfo(
                "http://orchestrator/webhooks/default",
                true,
                Set.of(WebhookInfo.COMMENTS, WebhookInfo.PULL_REQUESTS)
            )
        );

        var output = create(vcs);

        assertEquals(false, output.get("webhookMissing"));
        assertTrue(vcs.postedPrComments.isEmpty());
    }

    @Test
    void notKnowingTheWebhooksSaysNothing() {
        var vcs = new StubVcsClient(); // cannot list webhooks

        var output = create(vcs);

        assertEquals(false, output.get("webhookMissing"));
        assertTrue(vcs.postedPrComments.isEmpty());
    }

    @Test
    void aReusedPullRequestIsNotToldTwice() {
        var vcs = new StubVcsClient();
        vcs.webhooks = List.of();
        create(vcs);
        assertEquals(1, vcs.postedPrComments.size());

        var output = create(vcs);

        assertEquals(true, output.get("reused"));
        assertEquals(false, output.get("webhookMissing"));
        assertEquals(1, vcs.postedPrComments.size(), "the notice was posted when the pull request opened");
    }

    @Test
    void aFailureToPostTheNoticeDoesNotFailTheStep() {
        var vcs = new StubVcsClient() {
            @Override
            public void createPrComment(String owner, String repo, int prNumber, String body) {
                throw new RuntimeException("403");
            }
        };
        vcs.webhooks = List.of();

        var output = create(vcs);

        assertEquals(true, output.get("webhookMissing"));
        assertEquals(1, vcs.createdPrs.size());
    }
}
