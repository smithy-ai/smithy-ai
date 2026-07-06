package dev.smithyai.knowledgebase.service.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.smithyai.knowledgebase.config.KnowledgebaseConfig;
import dev.smithyai.knowledgebase.config.KnowledgebaseConfig.RepositoryConfig;
import dev.smithyai.knowledgebase.service.git.GitSyncService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

class IndexingServiceTest {

    private static final RepositoryConfig REPO = new RepositoryConfig(
        "owner/repo",
        "https://example.test/repo.git",
        "main"
    );
    private static final String KEY = "owner--repo";

    private final MarkdownLoaderService markdownLoaderService = mock(MarkdownLoaderService.class);
    private final VectorStoreFactory vectorStoreFactory = mock(VectorStoreFactory.class);
    private final IndexStatusService indexStatusService = mock(IndexStatusService.class);
    private final GitSyncService gitSyncService = mock(GitSyncService.class);
    private final VectorStore vectorStore = mock(VectorStore.class);

    @Test
    void skipsStartupReindexWhenSyncedCommitIsAlreadyIndexed() throws Exception {
        IndexingService service = spy(newService());
        when(indexStatusService.readVersion(KEY)).thenReturn(1);
        when(indexStatusService.readCommit(KEY)).thenReturn("abc123");
        when(vectorStoreFactory.collectionExists(KEY + "_v1")).thenReturn(true);
        when(vectorStoreFactory.loadVectorStore(KEY + "_v1")).thenReturn(vectorStore);
        when(gitSyncService.syncRepository(REPO)).thenReturn("abc123");

        service.onStartup();

        verify(service, never()).buildAndSwapIndex(any(), any());
    }

    @Test
    void reindexesOnStartupWhenSyncedCommitChanged() throws Exception {
        IndexingService service = spy(newService());
        when(indexStatusService.readVersion(KEY)).thenReturn(1);
        when(indexStatusService.readCommit(KEY)).thenReturn("abc123");
        when(vectorStoreFactory.collectionExists(KEY + "_v1")).thenReturn(true);
        when(vectorStoreFactory.loadVectorStore(KEY + "_v1")).thenReturn(vectorStore);
        when(gitSyncService.syncRepository(REPO)).thenReturn("def456");
        doReturn(Map.of()).when(service).buildAndSwapIndex(REPO, "def456");

        service.onStartup();

        verify(service).buildAndSwapIndex(REPO, "def456");
    }

    @Test
    void reindexesOnStartupWhenPersistedCollectionIsMissingEvenIfCommitMatches() throws Exception {
        IndexingService service = spy(newService());
        when(indexStatusService.readVersion(KEY)).thenReturn(1);
        when(indexStatusService.readCommit(KEY)).thenReturn("abc123");
        when(vectorStoreFactory.collectionExists(KEY + "_v1")).thenReturn(false);
        when(gitSyncService.syncRepository(REPO)).thenReturn("abc123");
        doReturn(Map.of()).when(service).buildAndSwapIndex(REPO, "abc123");

        service.onStartup();

        verify(service).buildAndSwapIndex(REPO, "abc123");
    }

    @Test
    void buildAndSwapIndexPersistsIndexedCommit() throws Exception {
        when(gitSyncService.repoLocalPath(REPO)).thenReturn(Path.of("/tmp/owner--repo"));
        when(markdownLoaderService.loadMarkdownFiles(Path.of("/tmp/owner--repo"))).thenReturn(List.of());
        when(vectorStoreFactory.createVectorStore(KEY + "_v1")).thenReturn(vectorStore);
        IndexingService service = newService();

        Map<String, Object> result = service.buildAndSwapIndex(REPO, "abc123");

        assertThat(result.get("commitHash")).isEqualTo("abc123");
        verify(indexStatusService).writeStatus(KEY, 1, "abc123");
    }

    private IndexingService newService() {
        return new IndexingService(
            markdownLoaderService,
            vectorStoreFactory,
            indexStatusService,
            gitSyncService,
            new KnowledgebaseConfig(null, null, null, 0, null, null, List.of(REPO))
        );
    }
}
