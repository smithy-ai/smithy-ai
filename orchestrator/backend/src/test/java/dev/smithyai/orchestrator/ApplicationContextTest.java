package dev.smithyai.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.smithyai.orchestrator.config.RepositoryConfigResolver;
import dev.smithyai.orchestrator.runtime.actions.ActionRegistry;
import dev.smithyai.orchestrator.runtime.engine.WorkflowRegistry;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import dev.smithyai.orchestrator.workflow.WorkflowService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Boots the whole application context. This is the cheapest check that the bean
 * graph still wires up — constructor-injection changes in the workflow factories
 * fail here rather than at container start on a real deployment.
 *
 * <p>The startup container-recovery pass is an ApplicationReadyEvent listener,
 * which {@code @SpringBootTest} does not fire, so no Docker is required.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
        "CLAUDE_CODE_OAUTH_TOKEN=test-token",
        "VCS_PROVIDER=forgejo",
        "FORGEJO_URL=http://forgejo.invalid:3000",
        "SMITHY_FORGEJO_TOKEN=test-smithy-token",
        "ARCHITECT_FORGEJO_TOKEN=test-architect-token",
        // Without this the datasource points at /config, which does not exist
        // outside the container.
        "DB_PATH=build/tmp/smithy-context-test.db",
    }
)
class ApplicationContextTest {

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private RepositoryConfigResolver repositoryConfigResolver;

    @Autowired
    private RunStore runStore;

    @Autowired
    private WorkflowRegistry workflows;

    @Autowired
    private ActionRegistry actions;

    @Test
    void contextLoads() {
        assertNotNull(workflowService);
        assertNotNull(repositoryConfigResolver);
    }

    /**
     * Also proves Flyway actually migrated the configured datasource at startup:
     * without the schema this insert fails.
     */
    @Test
    void runStoreIsMigratedAndUsable() {
        var run = runStore.create("smithy-development", "v1", "refine", null);
        assertEquals("refine", runStore.find(run.id()).orElseThrow().state());
    }

    /**
     * The strongest check the built-in definitions get without a real provider:
     * a workflow only reaches the registry if every action it names exists and
     * every capability those actions need is supported here. A typo in a
     * {@code uses:} or an action that was never written fails this.
     */
    @Test
    void everyBuiltInWorkflowIsRunnable() {
        var names = workflows
            .all()
            .stream()
            .map(w -> w.metadata().name())
            .sorted()
            .toList();
        assertEquals(
            List.of("architect-learn", "architect-review", "feature-coordinator", "smithy-development"),
            names
        );
    }

    /** Every action a definition may name is a bean, with no duplicate types. */
    @Test
    void theActionRegistryIsPopulated() {
        assertNotNull(actions.find("agent.runStructured").orElse(null));
        assertNotNull(actions.find("run.wave").orElse(null));
        assertNotNull(actions.find("gate.await").orElse(null));
    }

    @Test
    void theEngineIsWhatHandlesEvents() {
        // There are no workflow factories left to register: every flow is a
        // definition, and WorkflowService is a way in to the engine.
        assertNotNull(workflows);
        assertEquals(4, workflows.all().size());
    }
}
