package dev.smithyai.orchestrator.web;

import static org.junit.jupiter.api.Assertions.*;

import dev.smithyai.orchestrator.model.PrContext;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Hearing that a pull request was opened, and waiting to. */
class WebhookArrivalsTest {

    private static WorkflowEvent opened(String owner, String repo, int number) {
        var info = new RepoInfo(owner, repo, "https://git.invalid/" + owner + "/" + repo, "gitlab-main");
        return new WorkflowEvent.PrOpened(new PrContext(info, number, "A thing", "", false, "smithy/7", "main"));
    }

    @Test
    void anOpeningNotedBeforeAnyoneAsksIsStillThere() {
        var arrivals = new WebhookArrivals();
        arrivals.note(opened("acme", "app", 7));

        assertTrue(arrivals.heardOf("acme", "app", 7));
        assertTrue(arrivals.awaitPullRequest("acme", "app", 7, Duration.ZERO));
    }

    @Test
    void spellingOfOwnerAndRepositoryDoesNotMatter() {
        var arrivals = new WebhookArrivals();
        arrivals.pullRequestOpened("Acme", "App", 7);

        assertTrue(arrivals.heardOf("acme", "app", 7));
    }

    @Test
    void anotherPullRequestOrRepositoryIsNotThisOne() {
        var arrivals = new WebhookArrivals();
        arrivals.pullRequestOpened("acme", "app", 7);

        assertFalse(arrivals.heardOf("acme", "app", 8));
        assertFalse(arrivals.heardOf("acme", "other", 7));
        assertFalse(arrivals.awaitPullRequest("acme", "app", 8, Duration.ofMillis(50)));
    }

    @Test
    void onlyAnOpeningIsNoted() {
        var arrivals = new WebhookArrivals();
        var info = new RepoInfo("acme", "app", "https://git.invalid/acme/app", "gitlab-main");
        arrivals.note(new WorkflowEvent.PrMerged(new PrContext(info, 7, "A thing", "", true, "smithy/7", "main")));

        assertFalse(arrivals.heardOf("acme", "app", 7));
    }

    @Test
    void aWaiterWakesWhenTheOpeningArrives() throws Exception {
        var arrivals = new WebhookArrivals();
        var deliver = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            arrivals.pullRequestOpened("acme", "app", 7);
        });
        deliver.start();

        long started = System.nanoTime();
        boolean heard = arrivals.awaitPullRequest("acme", "app", 7, Duration.ofSeconds(10));
        deliver.join();

        assertTrue(heard);
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0);
    }
}
