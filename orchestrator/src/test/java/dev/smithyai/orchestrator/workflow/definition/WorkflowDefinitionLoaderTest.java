package dev.smithyai.orchestrator.workflow.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowDefinitionLoaderTest {

    private final WorkflowDefinitionParser parser = new WorkflowDefinitionParser();

    @Test
    void loadsRepositoryWorkflowsInStableOrder() {
        VcsClient vcsClient = mock(VcsClient.class);
        var repo = new RepoInfo("acme", "shop", "");
        when(
            vcsClient.listRepositoryFiles("acme", "shop", WorkflowDefinitionLoader.REPOSITORY_WORKFLOW_DIR, null)
        ).thenReturn(List.of(".smithy/workflows/b.yml", ".smithy/workflows/ignored.md", ".smithy/workflows/a.yaml"));
        when(vcsClient.readRepositoryFile("acme", "shop", ".smithy/workflows/a.yaml", null)).thenReturn(
            Optional.of(WorkflowDefinitionParserTest.validWorkflow("a"))
        );
        when(vcsClient.readRepositoryFile("acme", "shop", ".smithy/workflows/b.yml", null)).thenReturn(
            Optional.of(WorkflowDefinitionParserTest.validWorkflow("b"))
        );

        var loader = new WorkflowDefinitionLoader(parser, vcsClient, null);

        var workflows = loader.loadRepositoryWorkflows(repo);

        assertEquals(
            List.of("a", "b"),
            workflows
                .stream()
                .map(w -> w.definition().metadata().name())
                .toList()
        );
        assertEquals("acme/shop:.smithy/workflows/a.yaml", workflows.getFirst().source());
    }

    @Test
    void loadsGlobalWorkflows(@TempDir Path tempDir) throws Exception {
        java.nio.file.Files.writeString(
            tempDir.resolve("smithy.yml"),
            WorkflowDefinitionParserTest.validWorkflow("smithy")
        );
        java.nio.file.Files.writeString(tempDir.resolve("notes.txt"), "ignored");
        VcsClient vcsClient = mock(VcsClient.class);
        var loader = new WorkflowDefinitionLoader(parser, vcsClient, null);

        var workflows = loader.loadGlobalWorkflows(tempDir);

        assertEquals(1, workflows.size());
        assertEquals("smithy", workflows.getFirst().definition().metadata().name());
    }

    @Test
    void repositoryWorkflowOverridesGlobalWorkflow(@TempDir Path tempDir) throws Exception {
        java.nio.file.Files.writeString(
            tempDir.resolve("smithy.yml"),
            WorkflowDefinitionParserTest.validWorkflow("smithy")
        );
        VcsClient vcsClient = mock(VcsClient.class);
        var repo = new RepoInfo("acme", "shop", "");
        when(
            vcsClient.listRepositoryFiles("acme", "shop", WorkflowDefinitionLoader.REPOSITORY_WORKFLOW_DIR, null)
        ).thenReturn(List.of(".smithy/workflows/smithy.yml"));
        when(vcsClient.readRepositoryFile("acme", "shop", ".smithy/workflows/smithy.yml", null)).thenReturn(
            Optional.of(WorkflowDefinitionParserTest.validWorkflow("smithy"))
        );

        var loader = new WorkflowDefinitionLoader(parser, vcsClient, null) {
            @Override
            public List<LoadedWorkflowDefinition> loadBuiltInWorkflows() {
                return List.of();
            }

            @Override
            public List<LoadedWorkflowDefinition> loadGlobalWorkflows(Path directory) {
                return super.loadGlobalWorkflows(tempDir);
            }
        };

        var workflows = loader.loadEffectiveWorkflows(repo);

        assertEquals(1, workflows.size());
        assertEquals("acme/shop:.smithy/workflows/smithy.yml", workflows.getFirst().source());
    }
}
