package dev.smithyai.orchestrator;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.smithyai.orchestrator.config.RepositoryConfigResolver;
import dev.smithyai.orchestrator.workflow.WorkflowService;
import dev.smithyai.orchestrator.workflow.shared.AbstractWorkflowFactory;
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
    }
)
class ApplicationContextTest {

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private RepositoryConfigResolver repositoryConfigResolver;

    @Autowired
    private List<AbstractWorkflowFactory<?>> factories;

    @Test
    void contextLoads() {
        assertNotNull(workflowService);
        assertNotNull(repositoryConfigResolver);
    }

    @Test
    void allWorkflowFactoriesAreRegistered() {
        var names = factories
            .stream()
            .map(f -> f.getClass().getSimpleName())
            .sorted()
            .toList();
        // Smithy plus the two architect flows. The foreman is deliberately absent.
        org.junit.jupiter.api.Assertions.assertEquals(
            List.of("ArchitectLearnFactory", "ArchitectReviewFactory", "SmithyWorkflowFactory"),
            names
        );
    }
}
