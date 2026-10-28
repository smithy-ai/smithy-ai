package dev.smithyai.orchestrator.web;

import static org.junit.jupiter.api.Assertions.*;

import dev.smithyai.orchestrator.web.WebhookDeduplicator.Delivery;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebhookDeduplicatorTest {

    private Instant now = Instant.parse("2026-10-27T11:03:49Z");
    private final WebhookDeduplicator deduplicator = new WebhookDeduplicator(() -> now);

    private void later(Duration duration) {
        now = now.plus(duration);
    }

    @Test
    void theFirstDeliveryPassesAndItsRepeatDoesNot() {
        assertTrue(deduplicator.firstDelivery(new Delivery("jira|id|abc", "jira|body|1111")));
        assertFalse(deduplicator.firstDelivery(new Delivery("jira|id|abc", "jira|body|1111")));
    }

    @Test
    void aMatchOnAnyKeyIsEnoughToDrop() {
        // Two registrations of the same webhook: different delivery ids, same body.
        assertTrue(deduplicator.firstDelivery(new Delivery("jira|id|first", "jira|body|same")));
        assertFalse(deduplicator.firstDelivery(new Delivery("jira|id|second", "jira|body|same")));

        // A provider retry: same delivery id, and the body hash was seen too.
        assertFalse(deduplicator.firstDelivery(new Delivery("jira|id|first", "jira|body|other")));
    }

    @Test
    void distinctDeliveriesBothPass() {
        assertTrue(deduplicator.firstDelivery(new Delivery("jira|id|a", "jira|body|1")));
        assertTrue(deduplicator.firstDelivery(new Delivery("jira|id|b", "jira|body|2")));
    }

    @Test
    void blankKeysNeverCollide() {
        // A provider with no delivery-id header contributes an empty key; two
        // different bodies must not be treated as duplicates because of it.
        assertTrue(deduplicator.firstDelivery(new Delivery("", "gitlab|body|1")));
        assertTrue(deduplicator.firstDelivery(new Delivery("", "gitlab|body|2")));
    }

    /**
     * Observed live: a plan approval whose transition ran for 37 minutes. Jira
     * kept retrying it, and every retry past the old five-minute window was
     * answered on the story once the run was free.
     */
    @Test
    void aRetryWhileTheFirstIsStillBeingProcessedIsDroppedHoweverLongThatTakes() {
        var approval = new Delivery("jira|id|approval", "jira|body|approval");
        assertTrue(deduplicator.firstDelivery(approval));

        later(Duration.ofMinutes(37));

        assertFalse(deduplicator.firstDelivery(approval));
    }

    /** The fourth comment of that incident: a retry nine minutes after the run was free. */
    @Test
    void afterProcessingTheDeliveryIdOutlivesTheBody() {
        var approval = new Delivery("jira|id|approval", "jira|body|approval");
        assertTrue(deduplicator.firstDelivery(approval));
        deduplicator.processed(approval);

        later(Duration.ofMinutes(9));
        assertFalse(deduplicator.firstDelivery(new Delivery("jira|id|approval", "jira|body|retried")));
        var sameBody = new Delivery("jira|id|unrelated", "jira|body|approval");
        assertTrue(
            deduplicator.firstDelivery(sameBody),
            "a body seen nine minutes ago is no longer a double registration"
        );
        deduplicator.processed(sameBody);

        later(Duration.ofHours(1));
        assertTrue(deduplicator.firstDelivery(approval));
    }

    @Test
    void aDeliveryThatFailedLetsItsRetryThrough() {
        var delivery = new Delivery("gitlab|id|note", "gitlab|body|note");
        assertTrue(deduplicator.firstDelivery(delivery));
        deduplicator.failed(delivery);

        assertTrue(deduplicator.firstDelivery(delivery));
    }

    /**
     * A dropped copy must not leave its keys behind: nothing will ever report
     * it processed, so a key it claimed would be remembered until a restart.
     */
    @Test
    void aDroppedDuplicateClaimsNoneOfItsKeys() {
        var original = new Delivery("jira|id|one", "jira|body|one");
        assertTrue(deduplicator.firstDelivery(original));
        assertFalse(deduplicator.firstDelivery(new Delivery("jira|id|one", "jira|body|two")));
        deduplicator.processed(original);

        assertTrue(deduplicator.firstDelivery(new Delivery("jira|id|two", "jira|body|two")));
    }
}
