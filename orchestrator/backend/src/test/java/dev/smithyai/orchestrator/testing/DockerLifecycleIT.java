package dev.smithyai.orchestrator.testing;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.config.*;
import dev.smithyai.orchestrator.runtime.actions.*;
import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.runtime.store.RunEnvironment;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import dev.smithyai.orchestrator.service.docker.ContainerService;
import dev.smithyai.orchestrator.service.docker.DockerCli;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The container seam against a real Docker daemon.
 *
 * <p>Everything else is verified against {@link FakeDockerCli}, which replaces
 * the process boundary. That catches a great deal, but it cannot catch the
 * things that only exist on the other side of the boundary: whether
 * {@code smithy-init} actually completes, whether the branch it was told to
 * create exists afterwards, whether the state file survives a restart, and
 * whether {@code waitForInit} recognises success.
 *
 * <p>Clones {@code file:///seed.git}, a bare repository baked into the test
 * image, so no VCS server is involved — the container lifecycle is what is
 * under test, not the provider.
 *
 * <p>Skipped unless Docker is running and the test image is present. Build it
 * with the instructions in {@code docs/src/setup/demo.md}; without it, the rest
 * of the suite still runs.
 */
class DockerLifecycleIT {

    private static final String IMAGE = "claude-task-seed:test";
    private static final String NETWORK = "smithy-it-net";
    private static final String CONTAINER = "smithy-it.acme.app.1";

    @TempDir
    Path tempDir;

    private ContainerService containers;
    private RunEnvironments environments;
    private RunStore store;

    @BeforeAll
    static void requireDockerAndImage() {
        org.junit.jupiter.api.Assumptions.assumeTrue(shell("docker info") == 0, "Docker is not running");
        org.junit.jupiter.api.Assumptions.assumeTrue(
            shell("docker image inspect " + IMAGE) == 0,
            IMAGE + " is not built — see the integration-test note in docs/src/setup/demo.md"
        );
        // A user-defined network, because that is what a real deployment uses.
        shell("docker network create " + NETWORK);
    }

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("runs.db") + "?foreign_keys=on");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        store = new RunStore(JdbcClient.create((DataSource) dataSource), new ObjectMapper());

        var dockerConfig = new DockerConfig("docker", NETWORK, IMAGE, null);
        containers = new ContainerService(
            dockerConfig,
            new ClaudeConfig("it-token", null, "claude-opus-5"),
            vcsProviderConfig(),
            botConfig(),
            new DockerCli(dockerConfig)
        );
        environments = new RunEnvironments(store, containers, new KnowledgebaseConfig(false, null, null));
    }

    @AfterEach
    void removeContainer() {
        shell("docker rm -f " + CONTAINER);
    }

    // ── The tests ────────────────────────────────────────────

    @Test
    void containerInitClonesTheRepositoryAndCreatesTheWorkBranch() {
        var run = store.create("smithy-development", "1", "refine", null);
        var action = new ContainerInitAction(environments, dockerConfig());

        var result = action.execute(context(run), initInputs());

        assertEquals(CONTAINER, result.get("name"));
        assertEquals(true, result.get("created"));

        // smithy-init reported success rather than the orchestrator assuming it.
        var session = environments.container(store.find(run.id()).orElseThrow());
        assertEquals(0, session.exec("test", "-f", "/tmp/smithy-init-done").exitCode(), "init completed");

        // The branch it was told to create is the one checked out.
        var branch = session.exec("sh", "-c", "cd /workspace && git rev-parse --abbrev-ref HEAD");
        assertEquals(0, branch.exitCode(), branch.stderr());
        assertEquals("smithy/it-1", branch.stdout().strip());

        // And the repository really was cloned, not just the directory made.
        var seeded = session.exec("sh", "-c", "cat /workspace/README.md");
        assertEquals(0, seeded.exitCode(), seeded.stderr());
        assertTrue(seeded.stdout().contains("seed repository"), seeded.stdout());
    }

    @Test
    void theRunHoldsTheContainerAndCanBeFoundFromIt() {
        var run = store.create("smithy-development", "1", "refine", null);
        new ContainerInitAction(environments, dockerConfig()).execute(context(run), initInputs());

        assertEquals(
            List.of(CONTAINER),
            store.findEnvironmentNames(run.id(), RunEnvironment.CONTAINER),
            "the run records what it holds"
        );
        assertEquals(
            run.id(),
            store.findByEnvironment(RunEnvironment.CONTAINER, CONTAINER).orElseThrow().id(),
            "and an event about the container finds the run"
        );
    }

    @Test
    void initIsNotRepeatedForARunThatAlreadyHasAContainer() {
        var run = store.create("smithy-development", "1", "refine", null);
        var action = new ContainerInitAction(environments, dockerConfig());
        action.execute(context(run), initInputs());

        // Cloning again would cost minutes and throw away the working tree.
        var second = action.execute(context(store.find(run.id()).orElseThrow()), initInputs());

        assertEquals(false, second.get("created"));
        assertEquals(CONTAINER, second.get("name"));
    }

    @Test
    void theStateFileSurvivesAContainerRestart() {
        var run = store.create("smithy-development", "1", "refine", null);
        new ContainerInitAction(environments, dockerConfig()).execute(context(run), initInputs());
        var session = environments.container(store.find(run.id()).orElseThrow());
        session.updateState(state -> state.withSessionId("session-abc"));

        // An orchestrator restart is normal; so is the machine rebooting under
        // it. smithy-init re-runs on restart and must not wipe the workspace.
        assertEquals(0, shell("docker restart " + CONTAINER), "the container restarts");
        assertTrue(containers.ensureRunning(CONTAINER));

        var reread = containers.readStateSafe(CONTAINER).orElseThrow();
        assertEquals("session-abc", reread.sessionId(), "the agent session id is still there");
        assertEquals("refine", reread.stage());

        var branch = session.exec("sh", "-c", "cd /workspace && git rev-parse --abbrev-ref HEAD");
        assertEquals("smithy/it-1", branch.stdout().strip(), "and so is the work branch");
    }

    @Test
    void execRunsCommandsWithTheEnvironmentItWasGiven() {
        var run = store.create("smithy-development", "1", "refine", null);
        new ContainerInitAction(environments, dockerConfig()).execute(context(run), initInputs());

        var git = new GitActions();
        var status = git.gitStatusAction(environments).execute(context(store.find(run.id()).orElseThrow()), Map.of());
        assertEquals(true, status.get("clean"), "a fresh clone has nothing uncommitted");
        assertEquals("smithy/it-1", status.get("branch"));

        // Values arrive as environment, so a path with a space in it stays one
        // argument — the bug that silently dropped an extra repository.
        var exec = git
            .execAction(environments)
            .execute(
                context(store.find(run.id()).orElseThrow()),
                Map.of(
                    "shell",
                    "mkdir -p \"$DIR\" && echo hi > \"$DIR/file.txt\" && cat \"$DIR/file.txt\"",
                    "env",
                    Map.of("DIR", "/workspace/a dir with spaces")
                )
            );
        assertEquals(0, exec.get("exitCode"), String.valueOf(exec.get("stderr")));
        assertEquals("hi", exec.get("stdout"));
    }

    @Test
    void destroyingTheRunsContainerLeavesTheRunBehind() {
        var run = store.create("smithy-development", "1", "refine", null);
        new ContainerInitAction(environments, dockerConfig()).execute(context(run), initInputs());
        store.appendEvent(run.id(), "plan_posted", null);

        environments.destroyContainer(store.find(run.id()).orElseThrow());

        assertFalse(containers.containerExists(CONTAINER), "the container is gone");
        assertTrue(store.findEnvironmentNames(run.id(), RunEnvironment.CONTAINER).isEmpty(), "and released");
        assertFalse(store.findEvents(run.id()).isEmpty(), "but the run's history is not");
    }

    @Test
    void aStoppedContainerIsStartedBeforeAStepRunsInIt() {
        var run = store.create("smithy-development", "1", "refine", null);
        new ContainerInitAction(environments, dockerConfig()).execute(context(run), initInputs());

        // A machine reboot, a docker stop, a restart policy that lost.
        assertEquals(0, shell("docker stop " + CONTAINER), "the container stops");

        var status = new GitActions()
            .gitStatusAction(environments)
            .execute(context(store.find(run.id()).orElseThrow()), Map.of());

        assertEquals("smithy/it-1", status.get("branch"), "the step ran, so the container was started for it");
    }

    @Test
    void aRunAdoptsAContainerNobodyHolds() {
        var first = store.create("smithy-development", "1", "refine", null);
        new ContainerInitAction(environments, dockerConfig()).execute(context(first), initInputs());
        // The record is gone but the container is not — a rebuilt store, or a
        // run that was removed while its container stayed.
        store.detachEnvironment(RunEnvironment.CONTAINER, CONTAINER);

        var second = store.create("smithy-development", "1", "refine", null);
        var result = new ContainerInitAction(environments, dockerConfig()).execute(context(second), initInputs());

        assertEquals(CONTAINER, result.get("name"));
        assertEquals(
            second.id(),
            store.findByEnvironment(RunEnvironment.CONTAINER, CONTAINER).orElseThrow().id(),
            "the later run holds it"
        );
        // Adopted, not rebuilt: the working tree is still the first run's.
        var branch = environments
            .container(store.find(second.id()).orElseThrow())
            .exec("sh", "-c", "cd /workspace && git rev-parse --abbrev-ref HEAD");
        assertEquals("smithy/it-1", branch.stdout().strip());
    }

    @Test
    void aContainerAnotherRunHoldsIsNotTakenFromIt() {
        var holder = store.create("smithy-development", "1", "refine", null);
        new ContainerInitAction(environments, dockerConfig()).execute(context(holder), initInputs());

        var other = store.create("smithy-development", "1", "refine", null);
        var action = new ContainerInitAction(environments, dockerConfig());
        var context = context(other);

        // Two runs in one container is two agents in one working tree.
        var refused = assertThrows(IllegalStateException.class, () -> action.execute(context, initInputs()));
        assertTrue(refused.getMessage().contains(holder.id()), refused.getMessage());
    }

    // ── Plumbing ─────────────────────────────────────────────

    private ActionContext context(dev.smithyai.orchestrator.runtime.store.Run run) {
        return new ActionContext(run, null, Map.of(), run.vars());
    }

    private static Map<String, Object> initInputs() {
        return Map.of(
            "name",
            CONTAINER,
            // Baked into the test image, so nothing has to serve it.
            "cloneUrl",
            "file:///seed.git",
            "branch",
            "smithy/it-1",
            "sourceBranch",
            "main",
            "stage",
            "refine"
        );
    }

    private static DockerConfig dockerConfig() {
        return new DockerConfig("docker", NETWORK, IMAGE, null);
    }

    private static BotConfig botConfig() {
        return new BotConfig(
            new BotConfig.BotEntry("smithy", "smithy@localhost"),
            new BotConfig.BotEntry("architect", "architect@localhost"),
            new BotConfig.BotEntry("coordinator", "coordinator@localhost")
        );
    }

    private static VcsProviderConfig vcsProviderConfig() {
        return new VcsProviderConfig(
            "forgejo",
            null,
            new VcsProviderConfig.ForgejoProviderConfig(
                "http://forgejo.invalid",
                "http://forgejo.invalid",
                null,
                "smithy-token",
                "architect-token",
                "coordinator-token"
            ),
            null,
            null,
            null
        );
    }

    private static int shell(String command) {
        try {
            var process = new ProcessBuilder("sh", "-c", command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            return process.waitFor();
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
