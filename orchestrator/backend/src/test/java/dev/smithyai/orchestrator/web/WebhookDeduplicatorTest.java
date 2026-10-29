package dev.smithyai.orchestrator.web;

import static org.junit.jupiter.api.Assertions.*;

import dev.smithyai.orchestrator.web.WebhookDeduplicator.Delivery;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WebhookDeduplicatorTest {

    private Instant now = Instant.parse("2026-10-27T11:03:49Z");
    private final WebhookDeduplicator deduplicator = new WebhookDeduplicator(() -> now);

    private void later(Duration duration) {
        now = now.plus(duration);
    }

    private boolean firstDelivery(Delivery delivery) {
        return deduplicator.claim(delivery).isPresent();
    }

    private WebhookDeduplicator.Claim claim(Delivery delivery) {
        return deduplicator.claim(delivery).orElseThrow();
    }

    @Test
    void theFirstDeliveryPassesAndItsRepeatDoesNot() {
        assertTrue(firstDelivery(new Delivery("jira|id|abc", "jira|body|1111")));
        assertFalse(firstDelivery(new Delivery("jira|id|abc", "jira|body|1111")));
    }

    @Test
    void aMatchOnAnyKeyIsEnoughToDrop() {
        // Two registrations of the same webhook: different delivery ids, same body.
        assertTrue(firstDelivery(new Delivery("jira|id|first", "jira|body|same")));
        assertFalse(firstDelivery(new Delivery("jira|id|second", "jira|body|same")));

        // A provider retry: same delivery id, and the body hash was seen too.
        assertFalse(firstDelivery(new Delivery("jira|id|first", "jira|body|other")));
    }

    @Test
    void distinctDeliveriesBothPass() {
        assertTrue(firstDelivery(new Delivery("jira|id|a", "jira|body|1")));
        assertTrue(firstDelivery(new Delivery("jira|id|b", "jira|body|2")));
    }

    @Test
    void blankKeysNeverCollide() {
        // A provider with no delivery-id header contributes an empty key; two
        // different bodies must not be treated as duplicates because of it.
        assertTrue(firstDelivery(new Delivery("", "gitlab|body|1")));
        assertTrue(firstDelivery(new Delivery("", "gitlab|body|2")));
    }

    /**
     * Observed live: a plan approval whose transition ran for 37 minutes. Jira
     * kept retrying it, and every retry past the old five-minute window was
     * answered on the story once the run was free.
     */
    @Test
    void aRetryWhileTheFirstIsStillBeingProcessedIsDroppedHoweverLongThatTakes() {
        var approval = new Delivery("jira|id|approval", "jira|body|approval");
        assertTrue(firstDelivery(approval));

        later(Duration.ofMinutes(37));

        assertFalse(firstDelivery(approval));
    }

    /** The fourth comment of that incident: a retry nine minutes after the run was free. */
    @Test
    void afterProcessingTheDeliveryIdOutlivesTheBody() {
        var approval = new Delivery("jira|id|approval", "jira|body|approval");
        deduplicator.processed(claim(approval), Set.of());

        later(Duration.ofMinutes(9));
        assertFalse(firstDelivery(new Delivery("jira|id|approval", "jira|body|retried")));
        var sameBody = deduplicator.claim(new Delivery("jira|id|unrelated", "jira|body|approval"));
        assertTrue(sameBody.isPresent(), "a body seen nine minutes ago is no longer a double registration");
        deduplicator.processed(sameBody.get(), Set.of());

        later(Duration.ofHours(1));
        assertTrue(firstDelivery(approval));
    }

    @Test
    void aDeliveryNothingActedOnLetsItsRetryThroughToEveryWorkflow() {
        var delivery = new Delivery("gitlab|id|note", "gitlab|body|note");
        deduplicator.failed(claim(delivery));

        assertFalse(claim(delivery).repeat());
    }

    /**
     * One comment reaching two workflows: the orchestrator replies on the
     * ticket, the implementer throws. The retry is owed to the implementer
     * alone; the orchestrator answering twice is the bug this class exists for.
     */
    @Test
    void aRetryGoesOnlyToTheWorkflowsThatFailedOnTheFirstCopy() {
        var comment = new Delivery("jira|id|comment", "jira|body|comment");
        deduplicator.processed(claim(comment), Set.of("implementer"));

        later(Duration.ofMinutes(2));
        var retry = claim(comment);

        assertTrue(retry.repeat());
        assertEquals(Set.of("implementer"), retry.owed());
        assertFalse(firstDelivery(comment), "in flight again while the retry runs");

        deduplicator.processed(retry, Set.of());
        assertFalse(firstDelivery(comment), "once the implementer acted, every workflow has");
    }

    @Test
    void aRetryThatFailsAgainStaysOwedToTheSameWorkflowsOnly() {
        var comment = new Delivery("jira|id|comment", "jira|body|comment");
        deduplicator.processed(claim(comment), Set.of("implementer"));

        // The retry could not even be read: nothing acted on it, but the
        // orchestrator already had, so it stays out of the next one too.
        deduplicator.failed(claim(comment));

        assertEquals(Set.of("implementer"), claim(comment).owed());
    }

    /** A double registration shares only the body; a workflow either copy saw act is not owed it. */
    @Test
    void twoCopiesOweOnlyWhatBothOwe() {
        deduplicator.processed(claim(new Delivery("jira|id|first", "jira|body|same")), Set.of("a", "b"));
        later(Duration.ofSeconds(1));
        deduplicator.processed(claim(new Delivery("jira|id|second", "jira|body|other")), Set.of("b", "c"));

        assertEquals(Set.of("b"), claim(new Delivery("jira|id|second", "jira|body|same")).owed());
    }

    /**
     * A dropped copy must not leave its keys behind: nothing will ever report
     * it processed, so a key it claimed would be remembered until a restart.
     */
    @Test
    void aDroppedDuplicateClaimsNoneOfItsKeys() {
        var original = claim(new Delivery("jira|id|one", "jira|body|one"));
        assertFalse(firstDelivery(new Delivery("jira|id|one", "jira|body|two")));
        deduplicator.processed(original, Set.of());

        assertTrue(firstDelivery(new Delivery("jira|id|two", "jira|body|two")));
    }
}
