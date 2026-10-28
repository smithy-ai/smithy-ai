package dev.smithyai.orchestrator.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Drops webhook deliveries the orchestrator has already seen.
 *
 * <p>Providers redeliver: a timeout on our side triggers a retry, and a
 * misconfigured tracker with the same webhook registered twice sends every
 * event twice. Observed live: one "approved" answered twice, one approval
 * delivered five times in four milliseconds, and two simultaneous assignment
 * deliveries racing two runs into existence — the second recreating the
 * first's same-named container and killing the agent turn inside it (exit
 * 137). Deduplication here is the first line of defence; the per-key run
 * creation lock in the engine is the second.
 *
 * <p>A delivery is identified by every key the caller can offer — the
 * provider's delivery id and a hash of the body — and is a duplicate if any
 * of them is still remembered. Two keys, because neither is complete alone: a
 * provider retry reuses the body but may or may not reuse the id, and two
 * registrations of the same webhook send the same body under two ids.
 *
 * <p>How long a key is remembered follows the delivery's life, not its
 * arrival. While the delivery is being processed its keys do not expire:
 * a transition can hold the request open for the length of an agent turn,
 * and the provider, timing out, retries all along. Observed live: a Jira
 * plan approval whose transition ran for 37 minutes, retried after the old
 * five-minute window lapsed, and each retry answered on the story once the
 * run was free — three comments in one second, a fourth nine minutes later.
 * Once processed, the provider's delivery id is kept for an hour, which
 * outlasts the retries scheduled while it ran, and the body hash for five
 * minutes, which is all a double registration needs. A delivery that failed
 * is forgotten at once: its retry is the provider doing the right thing.
 *
 * <p>In memory on purpose. After a restart nothing is remembered, so the
 * redelivery of an event whose transition the restart cut short gets through
 * and resumes it.
 */
@Component
public class WebhookDeduplicator {

    private static final Duration ID_WINDOW = Duration.ofHours(1);
    private static final Duration BODY_WINDOW = Duration.ofMinutes(5);

    /** Every key a caller offers for one delivery; either may be blank. */
    public record Delivery(String id, String body) {
        private Stream<String> keys() {
            return Stream.of(id, body).filter(key -> key != null && !key.isBlank());
        }
    }

    /** Key to the moment it may be forgotten; {@link Instant#MAX} while in flight. */
    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    private final Supplier<Instant> clock;

    public WebhookDeduplicator() {
        this(Instant::now);
    }

    /** For tests that need time to pass. */
    WebhookDeduplicator(Supplier<Instant> clock) {
        this.clock = clock;
    }

    /**
     * Claim a delivery. Synchronized because checking every key and recording
     * them is one decision: two copies arriving together must not both pass.
     *
     * @return true when this delivery is new and the caller should process it,
     *         then report back with {@link #processed} or {@link #failed};
     *         false when any of its keys is still remembered
     */
    public synchronized boolean firstDelivery(Delivery delivery) {
        Instant now = clock.get();
        seen.values().removeIf(until -> until.isBefore(now));

        if (delivery.keys().anyMatch(seen::containsKey)) return false;
        delivery.keys().forEach(key -> seen.put(key, Instant.MAX));
        return true;
    }

    /** The delivery was acted on; its repeats are duplicates for a while yet. */
    public void processed(Delivery delivery) {
        Instant now = clock.get();
        remember(delivery.id(), now.plus(ID_WINDOW));
        remember(delivery.body(), now.plus(BODY_WINDOW));
    }

    /** The delivery was not acted on; let its retry through. */
    public void failed(Delivery delivery) {
        delivery.keys().forEach(seen::remove);
    }

    private void remember(String key, Instant until) {
        if (key != null && !key.isBlank()) seen.put(key, until);
    }
}
