package dev.smithyai.knowledgebase.service.index;

import dev.smithyai.knowledgebase.config.KnowledgebaseConfig;
import dev.smithyai.knowledgebase.config.KnowledgebaseConfig.RepositoryConfig;
import dev.smithyai.knowledgebase.model.Chunk;
import dev.smithyai.knowledgebase.service.git.GitSyncService;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class IndexingService {

    private final MarkdownLoaderService markdownLoaderService;
    private final VectorStoreFactory vectorStoreFactory;
    private final IndexStatusService indexStatusService;
    private final GitSyncService gitSyncService;
    private final KnowledgebaseConfig config;

    private final ConcurrentHashMap<String, VectorStore> activeVectorStores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activeCollectionNames = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> collectionVersions = new ConcurrentHashMap<>();
    private final Set<String> indexingInProgress = ConcurrentHashMap.newKeySet();

    public IndexingService(
        MarkdownLoaderService markdownLoaderService,
        VectorStoreFactory vectorStoreFactory,
        IndexStatusService indexStatusService,
        GitSyncService gitSyncService,
        KnowledgebaseConfig config
    ) {
        this.markdownLoaderService = markdownLoaderService;
        this.vectorStoreFactory = vectorStoreFactory;
        this.indexStatusService = indexStatusService;
        this.gitSyncService = gitSyncService;
        this.config = config;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        List<RepositoryConfig> repos = config.repositories();
        if (repos == null || repos.isEmpty()) {
            log.info("No repositories configured, skipping startup sync");
            return;
        }

        for (RepositoryConfig repo : repos) {
            String key = repo.safeKey();

            // Restore persisted state
            int persistedVersion = indexStatusService.readVersion(key);
            collectionVersions.put(key, new AtomicInteger(persistedVersion));

            if (persistedVersion > 0) {
                String collectionName = key + "_v" + persistedVersion;
                if (vectorStoreFactory.collectionExists(collectionName)) {
                    VectorStore vs = vectorStoreFactory.loadVectorStore(collectionName);
                    activeVectorStores.put(key, vs);
                    activeCollectionNames.put(key, collectionName);
                    log.info("Restored collection {} for repo {}", collectionName, repo.name());
                }
            }

            // Sync and reindex
            try {
                log.info("Startup sync for repo {}", repo.name());
                gitSyncService.syncRepository(repo);
                buildAndSwapIndex(repo);
            } catch (Exception e) {
                log.error("Startup sync failed for repo {}: {}", repo.name(), e.getMessage());
            }
        }
    }

    public Map<String, Object> buildAndSwapIndex(RepositoryConfig repo) {
        String key = repo.safeKey();
        if (!indexingInProgress.add(key)) {
            throw new IllegalStateException("Indexing already in progress for " + repo.name());
        }

        try {
            Path knowledgeDir = gitSyncService.repoLocalPath(repo);
            log.info("Building index for {} from {}", repo.name(), knowledgeDir);

            List<Chunk> chunks = markdownLoaderService.loadMarkdownFiles(knowledgeDir);

            if (chunks.isEmpty()) {
                log.warn("No chunks found for repo {}", repo.name());
            }

            AtomicInteger version = collectionVersions.computeIfAbsent(key, k -> new AtomicInteger(0));
            int newVersion = version.incrementAndGet();
            String newCollectionName = key + "_v" + newVersion;

            log.info("Creating collection {}", newCollectionName);
            VectorStore newVectorStore = vectorStoreFactory.createVectorStore(newCollectionName);

            if (!chunks.isEmpty()) {
                List<Document> documents = chunks.stream().map(this::chunkToDocument).toList();
                newVectorStore.add(documents);
                log.info("Indexed {} documents into {}", documents.size(), newCollectionName);
                vectorStoreFactory.saveVectorStore(newVectorStore, newCollectionName);
            }

            String oldCollectionName = activeCollectionNames.put(key, newCollectionName);
            activeVectorStores.put(key, newVectorStore);

            log.info("Swapped {} from {} to {}", repo.name(), oldCollectionName, newCollectionName);

            indexStatusService.writeVersion(key, newVersion);

            if (oldCollectionName != null) {
                Thread.startVirtualThread(() -> {
                    try {
                        Thread.sleep(5000);
                        vectorStoreFactory.deleteCollection(oldCollectionName);
                    } catch (Exception e) {
                        log.error("Error deleting old collection {}: {}", oldCollectionName, e.getMessage());
                    }
                });
            }

            Map<String, Object> result = new HashMap<>();
            result.put("repoName", repo.name());
            result.put("collectionName", newCollectionName);
            result.put("documentCount", chunks.size());
            result.put("version", newVersion);
            return result;
        } finally {
            indexingInProgress.remove(key);
        }
    }

    public VectorStore getActiveVectorStore(String fullName) {
        return activeVectorStores.get(toSafeKey(fullName));
    }

    public String getActiveCollectionName(String fullName) {
        return activeCollectionNames.get(toSafeKey(fullName));
    }

    public boolean isIndexing(String fullName) {
        return indexingInProgress.contains(toSafeKey(fullName));
    }

    public boolean isInitialized(String fullName) {
        return activeCollectionNames.containsKey(toSafeKey(fullName));
    }

    private static String toSafeKey(String fullName) {
        return fullName.replace("/", "--");
    }

    public Map<String, String> allActiveCollections() {
        return Map.copyOf(activeCollectionNames);
    }

    private Document chunkToDocument(Chunk chunk) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("filePath", chunk.filePath());
        metadata.put("section", chunk.section());
        metadata.put("startLine", chunk.startLine());
        metadata.put("endLine", chunk.endLine());
        return new Document(chunk.content(), metadata);
    }
}
