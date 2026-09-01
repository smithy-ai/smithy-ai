package dev.smithyai.orchestrator.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * of them was seen inside the window. Two keys, because neither is complete
 * alone: a provider retry reuses the body but may or may not reuse the id,
 * and two registrations of the same webhook send the same body under two ids.
 */
@Component
public class WebhookDeduplicator {

    private static final Duration WINDOW = Duration.ofMinutes(5);

    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    /**
     * @return true when this delivery is new; false when any of its keys was
     *         already seen inside the window (and the caller should drop it)
     */
    public boolean firstDelivery(List<String> keys) {
        Instant now = Instant.now();
        seen.values().removeIf(at -> at.plus(WINDOW).isBefore(now));

        boolean fresh = true;
        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            if (seen.putIfAbsent(key, now) != null) fresh = false;
        }
        return fresh;
    }
}
