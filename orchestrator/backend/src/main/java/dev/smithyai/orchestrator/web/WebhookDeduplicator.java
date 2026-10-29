package dev.smithyai.orchestrator.web;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * minutes, which is all a double registration needs.
 *
 * <p>Failure is remembered per workflow, because one event can reach several
 * — an orchestrator replying on the ticket while an implementer changes the
 * PR — and a resend is how a workflow that threw on it gets another go. So a
 * repeat of a delivery some workflows failed on is let through for those
 * workflows only; the ones that acted on it see a duplicate. A delivery that
 * nothing acted on at all (it could not be read, say) is forgotten at once.
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

    /**
     * A delivery the caller may process.
     *
     * @param owed null when every workflow may act on it; otherwise a repeat,
     *             and only the workflows named here, which failed on an
     *             earlier copy, may
     */
    public record Claim(Delivery delivery, Set<String> owed) {
        public boolean repeat() {
            return owed != null;
        }
    }

    /**
     * What a key stands for.
     *
     * @param until the moment it may be forgotten; {@link Instant#MAX} while in flight
     * @param owed  the workflows that failed on it and may act on a repeat;
     *              empty once every workflow that took it acted on it
     */
    private record Remembered(Instant until, Set<String> owed) {
        static final Remembered IN_FLIGHT = new Remembered(Instant.MAX, Set.of());

        boolean inFlight() {
            return until.equals(Instant.MAX);
        }
    }

    private final Map<String, Remembered> seen = new ConcurrentHashMap<>();

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
     * @return the claim when the caller should process it, then report back
     *         with {@link #processed} or {@link #failed}; empty when any of
     *         its keys is in flight, or every workflow already acted on it
     */
    public synchronized Optional<Claim> claim(Delivery delivery) {
        Instant now = clock.get();
        seen.values().removeIf(remembered -> remembered.until().isBefore(now));

        var remembered = delivery.keys().map(seen::get).filter(Objects::nonNull).toList();
        if (remembered.stream().anyMatch(Remembered::inFlight)) return Optional.empty();

        // Two keys can remember two different copies (a double registration
        // shares only the body): a workflow is owed the delivery only if no
        // copy it took saw it act.
        Set<String> owed = null;
        for (var key : remembered) {
            if (owed == null) {
                owed = key.owed();
            } else {
                var both = new HashSet<>(owed);
                both.retainAll(key.owed());
                owed = Set.copyOf(both);
            }
        }
        if (owed != null && owed.isEmpty()) return Optional.empty();

        delivery.keys().forEach(key -> seen.put(key, Remembered.IN_FLIGHT));
        return Optional.of(new Claim(delivery, owed));
    }

    /**
     * The delivery was acted on. Its repeats are duplicates for a while yet,
     * except to the workflows that failed on it.
     *
     * @param failed the workflows that threw on it; empty when none did
     */
    public void processed(Claim claim, Set<String> failed) {
        Instant now = clock.get();
        var owed = Set.copyOf(failed);
        remember(claim.delivery().id(), new Remembered(now.plus(ID_WINDOW), owed));
        remember(claim.delivery().body(), new Remembered(now.plus(BODY_WINDOW), owed));
    }

    /** Nothing acted on the delivery; a repeat is owed what this copy was. */
    public void failed(Claim claim) {
        if (claim.repeat()) {
            processed(claim, claim.owed());
        } else {
            claim.delivery().keys().forEach(seen::remove);
        }
    }

    private void remember(String key, Remembered remembered) {
        if (key != null && !key.isBlank()) seen.put(key, remembered);
    }
}
