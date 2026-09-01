package dev.smithyai.orchestrator.web;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class WebhookDeduplicatorTest {

    private final WebhookDeduplicator deduplicator = new WebhookDeduplicator();

    @Test
    void theFirstDeliveryPassesAndItsRepeatDoesNot() {
        assertTrue(deduplicator.firstDelivery(List.of("jira|id|abc", "jira|body|1111")));
        assertFalse(deduplicator.firstDelivery(List.of("jira|id|abc", "jira|body|1111")));
    }

    @Test
    void aMatchOnAnyKeyIsEnoughToDrop() {
        // Two registrations of the same webhook: different delivery ids, same body.
        assertTrue(deduplicator.firstDelivery(List.of("jira|id|first", "jira|body|same")));
        assertFalse(deduplicator.firstDelivery(List.of("jira|id|second", "jira|body|same")));

        // A provider retry: same delivery id, and the body hash was seen too.
        assertFalse(deduplicator.firstDelivery(List.of("jira|id|first", "jira|body|other")));
    }

    @Test
    void distinctDeliveriesBothPass() {
        assertTrue(deduplicator.firstDelivery(List.of("jira|id|a", "jira|body|1")));
        assertTrue(deduplicator.firstDelivery(List.of("jira|id|b", "jira|body|2")));
    }

    @Test
    void blankKeysNeverCollide() {
        // A provider with no delivery-id header contributes an empty key; two
        // different bodies must not be treated as duplicates because of it.
        assertTrue(deduplicator.firstDelivery(List.of("", "gitlab|body|1")));
        assertTrue(deduplicator.firstDelivery(List.of("", "gitlab|body|2")));
    }
}
