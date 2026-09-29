package dev.smithyai.orchestrator.runtime.actions;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.runtime.store.Run;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import dev.smithyai.orchestrator.testing.StubVcsClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Opening a pull request in a repository that cannot report back leaves a
 * note on it saying so, and a flag on the run.
 *
 * <p>Whether it can report back is judged from the delivery of the opening
 * itself, not from the repository's hook list: on GitHub and GitLab the hook
 * usually lives on the organisation or group, where the repository lists
 * nothing and the events arrive all the same.
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
        "main",
        // Short, so a repository that never answers does not hold the test a minute.
        "webhookTimeout",
        "1s"
    );

    @TempDir
    Path tempDir;

    private RunStore store;
    private Run run;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("runs.db") + "?foreign_keys=on");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        store = new RunStore(JdbcClient.create((DataSource) dataSource), new ObjectMapper());
        run = store.create("smithy-development", "1", "building", null);
    }

    private Map<String, Object> create(StubVcsClient vcs) {
        return create(vcs, INPUT);
    }

    private Map<String, Object> create(StubVcsClient vcs, Map<String, Object> input) {
        var action = new PullRequestActions().prCreateAction(vcs.asRegistry(), vcs.arrivals, store);
        return action.execute(new ActionContext(run, null, Map.of(), Map.of()), input);
    }

    @Test
    void aRepositoryWhoseOpeningNeverArrivesGetsANoticeOnTheNewPullRequest() {
        var vcs = new StubVcsClient();
        vcs.deliversWebhooks = false;

        var output = create(vcs);

        assertEquals(true, output.get("webhookMissing"));
        assertEquals(1, vcs.postedPrComments.size());
        var posted = vcs.postedPrComments.getFirst();
        assertEquals(vcs.createdPrs.getFirst().number(), posted.number());
        assertTrue(posted.body().contains("/webhooks/default"), posted.body());
        assertTrue(posted.body().contains("waited a second"), posted.body());
    }

    @Test
    void theRunIsFlaggedForTheDashboardAndItsTimelineNamesThePullRequest() {
        var vcs = new StubVcsClient();
        vcs.deliversWebhooks = false;

        create(vcs);

        var flagged = store.find(run.id()).orElseThrow();
        assertEquals(true, flagged.vars().get(PullRequestActions.WEBHOOK_MISSING_VAR));
        var event = store
            .findEvents(run.id())
            .stream()
            .filter(e -> e.type().equals("webhook.missing"))
            .findFirst()
            .orElseThrow();
        assertEquals("acme", event.payload().get("owner"));
        assertEquals("app", event.payload().get("repo"));
        assertEquals(vcs.createdPrs.getFirst().number(), event.payload().get("number"));
        assertEquals("default", event.payload().get("connector"));
    }

    @Test
    void aRepositoryThatReportsBackGetsNoNoticeAndNoFlag() {
        var vcs = new StubVcsClient(); // delivers its webhook, like a provider with one

        var output = create(vcs);

        assertEquals(false, output.get("webhookMissing"));
        assertTrue(vcs.postedPrComments.isEmpty());
        assertNull(store.find(run.id()).orElseThrow().vars().get(PullRequestActions.WEBHOOK_MISSING_VAR));
    }

    @Test
    void anOpeningDeliveredWhileWaitingCountsAsHeard() throws Exception {
        var vcs = new StubVcsClient();
        vcs.deliversWebhooks = false;
        var input = new HashMap<>(INPUT);
        input.put("webhookTimeout", "10s");

        // The provider delivers a moment after the API call returned.
        var late = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            vcs.arrivals.pullRequestOpened("acme", "app", 100);
        });
        late.start();
        long started = System.nanoTime();
        var output = create(vcs, input);
        late.join();

        assertEquals(false, output.get("webhookMissing"));
        assertTrue(vcs.postedPrComments.isEmpty());
        assertTrue(
            Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0,
            "woke on arrival"
        );
    }

    @Test
    void aReusedPullRequestIsNotToldTwice() {
        var vcs = new StubVcsClient();
        vcs.deliversWebhooks = false;
        create(vcs);
        assertEquals(1, vcs.postedPrComments.size());

        var output = create(vcs);

        assertEquals(true, output.get("reused"));
        assertEquals(false, output.get("webhookMissing"));
        assertEquals(1, vcs.postedPrComments.size(), "the notice was posted when the pull request opened");
    }

    @Test
    void aZeroTimeoutDoesNotListen() {
        var vcs = new StubVcsClient();
        vcs.deliversWebhooks = false;
        var input = new HashMap<>(INPUT);
        input.put("webhookTimeout", "0");

        var output = create(vcs, input);

        assertEquals(false, output.get("webhookMissing"));
        assertTrue(vcs.postedPrComments.isEmpty());
    }

    @Test
    void aTimeoutThatIsNotADurationIsRejectedByName() {
        var input = new HashMap<>(INPUT);
        input.put("webhookTimeout", "soon");

        var error = assertThrows(IllegalArgumentException.class, () -> create(new StubVcsClient(), input));

        assertTrue(error.getMessage().contains("webhookTimeout"), error.getMessage());
    }

    @Test
    void aFailureToPostTheNoticeDoesNotFailTheStep() {
        var vcs = new StubVcsClient() {
            @Override
            public void createPrComment(String owner, String repo, int prNumber, String body) {
                throw new RuntimeException("403");
            }
        };
        vcs.deliversWebhooks = false;

        var output = create(vcs);

        assertEquals(true, output.get("webhookMissing"));
        assertEquals(1, vcs.createdPrs.size());
    }

    @Test
    void withoutARunThereIsNothingToFlagButTheNoticeStillGoesUp() {
        var vcs = new StubVcsClient();
        vcs.deliversWebhooks = false;
        var action = new PullRequestActions().prCreateAction(vcs.asRegistry(), vcs.arrivals, store);

        var output = action.execute(new ActionContext(null, null, Map.of(), Map.of()), INPUT);

        assertEquals(true, output.get("webhookMissing"));
        assertEquals(1, vcs.postedPrComments.size());
    }
}
