package dev.smithyai.orchestrator.runtime.engine;

import static org.junit.jupiter.api.Assertions.*;

import dev.smithyai.orchestrator.config.ConnectorActorConfig;
import dev.smithyai.orchestrator.config.ConnectorConfig;
import dev.smithyai.orchestrator.config.OrchestratorConfig;
import dev.smithyai.orchestrator.config.WorkflowPolicyConfig;
import dev.smithyai.orchestrator.runtime.actions.ActionRegistry;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionLoader;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionParser;
import dev.smithyai.orchestrator.testing.StubVcsClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workflow acts as an identity, and whether this deployment has that identity
 * is knowable at load.
 *
 * <p>It used to be answered by falling back to the default account, which meant
 * the reviewer signed its review as the agent it was reviewing. Answering it by
 * throwing instead moved the problem rather than fixing it: the shipped config
 * configures one actor and the architect workflows declare another, so every
 * merged pull request started a run that died on its first repository step.
 */
class WorkflowActorTest {

    @TempDir
    Path tempDir;

    private static final String REVIEWER = """
        apiVersion: smithy.ai/v1alpha1
        kind: Workflow
        metadata:
          name: reviewer
        vars:
          actor: architect
        routing:
          - event: pr.merged
            action: create
            key: "{{ repo.fullName }}"
        state:
          initial: new
          terminal: done
          new:
            on: {}
          done:
            on: {}
        """;

    private List<String> load(String... actors) throws Exception {
        var definitions = Files.createDirectory(tempDir.resolve("workflows-" + String.join("-", actors)));
        Files.writeString(definitions.resolve("reviewer.yml"), REVIEWER);

        var identities = new java.util.LinkedHashMap<String, ConnectorActorConfig>();
        for (String actor : actors) {
            identities.put(actor, new ConnectorActorConfig(actor, null, null, null, null, null));
        }
        var config = new OrchestratorConfig(
            OrchestratorConfig.API_VERSION,
            OrchestratorConfig.KIND,
            null,
            null,
            null,
            null,
            Map.of("forgejo-main", new ConnectorConfig("forgejo", "", null, null, identities, null, null)),
            null,
            null,
            Map.of(),
            null,
            null
        );

        var stubs = new StubVcsClient();
        var registry = new WorkflowRegistry(
            new WorkflowDefinitionLoader(new WorkflowDefinitionParser()),
            new CapabilityValidator(new ActionRegistry(List.of())),
            new WorkflowPolicyConfig(null, null, definitions.toString()),
            stubs,
            stubs,
            stubs,
            config
        );
        registry.loadAll();
        return registry
            .all()
            .stream()
            .map(w -> w.metadata().name())
            .toList();
    }

    @Test
    void aWorkflowWhoseActorHasNoIdentityHereDoesNotLoad() throws Exception {
        assertFalse(
            load("smithy").contains("reviewer"),
            "a deployment with only a smithy identity cannot run a workflow that acts as the architect"
        );
    }

    /**
     * The case this exists for. The shipped orchestrator.yml configures one
     * actor and is baked into the image, while both architect workflows declare
     * another and architect-learn claims every merged pull request with no
     * guard at all.
     */
    @Test
    void theShippedArchitectWorkflowsStayOutOfASingleActorDeployment() throws Exception {
        var loaded = load("smithy");

        assertFalse(loaded.contains("architect-review"), loaded.toString());
        assertFalse(loaded.contains("architect-learn"), loaded.toString());
        assertFalse(loaded.contains("feature-coordinator"), loaded.toString());
    }

    @Test
    void configuringThatIdentityIsWhatMakesItRunnable() throws Exception {
        assertTrue(load("smithy", "architect").contains("reviewer"));
    }

    @Test
    void theIdentityMayLiveOnAnyConnector() throws Exception {
        // A reviewer configured only where it reviews is a real deployment, not
        // a misconfiguration, so the check is not tied to defaults.vcs.
        var definitions = Files.createDirectory(tempDir.resolve("split"));
        Files.writeString(definitions.resolve("reviewer.yml"), REVIEWER);
        var config = new OrchestratorConfig(
            OrchestratorConfig.API_VERSION,
            OrchestratorConfig.KIND,
            null,
            null,
            null,
            null,
            Map.of(
                "forgejo-main",
                new ConnectorConfig(
                    "forgejo",
                    "",
                    null,
                    null,
                    Map.of("smithy", new ConnectorActorConfig("smithy", null, null, null, null, null)),
                    null,
                    null
                ),
                "gitlab-review",
                new ConnectorConfig(
                    "gitlab",
                    "",
                    null,
                    null,
                    Map.of("architect", new ConnectorActorConfig("architect", null, null, null, null, null)),
                    null,
                    null
                )
            ),
            null,
            null,
            Map.of(),
            null,
            null
        );

        var stubs = new StubVcsClient();
        var registry = new WorkflowRegistry(
            new WorkflowDefinitionLoader(new WorkflowDefinitionParser()),
            new CapabilityValidator(new ActionRegistry(List.of())),
            new WorkflowPolicyConfig(null, null, definitions.toString()),
            stubs,
            stubs,
            stubs,
            config
        );
        registry.loadAll();

        assertTrue(
            registry
                .all()
                .stream()
                .anyMatch(w -> w.metadata().name().equals("reviewer"))
        );
    }
}
