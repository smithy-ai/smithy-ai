package dev.smithyai.orchestrator.web;

import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The pull requests this orchestrator has been told were opened.
 *
 * <p>How a step that just opened a pull request learns whether anything said
 * on it can reach the orchestrator. Reading the repository's webhook list does
 * not answer that: on GitHub and GitLab the hook is usually registered on the
 * organisation or group, where the repository itself lists nothing and the
 * events arrive all the same. What answers it is the delivery. Every provider
 * sends a pull-request event when one is opened, including one the bot opened
 * through the API, so the step waits a moment for that event and takes its
 * absence as the answer: if the opening did not arrive, neither will the
 * comments.
 *
 * <p>A delivery is noted here before the engine sees it, because the step
 * waiting for it may hold the run's lock and the delivery must not queue up
 * behind it. It is also kept rather than only signalled: the provider often
 * delivers before the API call that opened the pull request has returned, so
 * the step usually arrives after the note does.
 *
 * <p>Keyed by repository and number alone. A pull request that arrives through
 * any connector is one the orchestrator hears, whichever connector the step
 * acted through; two systems opening the same-numbered pull request in a
 * same-named repository inside the same hour is the only confusion that
 * allows, and its cost is a missing courtesy notice.
 */
@Component
public class WebhookArrivals {

    /** A pull request is opened once; nothing asks about it an hour later. */
    private static final Duration RETENTION = Duration.ofHours(1);

    private final Map<String, Instant> opened = new ConcurrentHashMap<>();
    private final Object arrived = new Object();

    /** Note an inbound event, if it is one this cares about. */
    public void note(WorkflowEvent event) {
        if (event instanceof WorkflowEvent.PrOpened pr) {
            pullRequestOpened(pr.prc().info().owner(), pr.prc().info().repo(), pr.prc().number());
        }
    }

    public void pullRequestOpened(String owner, String repo, int number) {
        Instant now = Instant.now();
        opened.values().removeIf(at -> at.plus(RETENTION).isBefore(now));
        opened.put(key(owner, repo, number), now);
        synchronized (arrived) {
            arrived.notifyAll();
        }
    }

    public boolean heardOf(String owner, String repo, int number) {
        return opened.containsKey(key(owner, repo, number));
    }

    /**
     * Wait for the opening of a pull request to be delivered.
     *
     * @return true as soon as it has been, false once the patience runs out
     *         without it
     */
    public boolean awaitPullRequest(String owner, String repo, int number, Duration patience) {
        String key = key(owner, repo, number);
        Instant deadline = Instant.now().plus(patience);
        synchronized (arrived) {
            while (!opened.containsKey(key)) {
                long left = Duration.between(Instant.now(), deadline).toMillis();
                if (left <= 0) return false;
                try {
                    arrived.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return opened.containsKey(key);
                }
            }
            return true;
        }
    }

    private static String key(String owner, String repo, int number) {
        // Providers treat owner and repository names case-insensitively, and
        // the step's inputs need not match the webhook's spelling.
        return (owner + "/" + repo + "!" + number).toLowerCase(Locale.ROOT);
    }
}
