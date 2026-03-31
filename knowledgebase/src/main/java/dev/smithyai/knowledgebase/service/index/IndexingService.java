package dev.smithyai.knowledgebase.service.index;

import dev.smithyai.knowledgebase.config.KnowledgebaseConfig;
import dev.smithyai.knowledgebase.model.Chunk;
import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class IndexingService {

    private final MarkdownLoaderService markdownLoaderService;
    private final VectorStoreFactory vectorStoreFactory;
    private final IndexStatusService indexStatusService;
    private final String knowledgeDir;

    private final AtomicReference<String> activeCollectionName = new AtomicReference<>(null);
    private final AtomicReference<VectorStore> activeVectorStore = new AtomicReference<>(null);
    private final AtomicInteger collectionVersion = new AtomicInteger(0);
    private final AtomicBoolean indexingInProgress = new AtomicBoolean(false);

    public IndexingService(
        MarkdownLoaderService markdownLoaderService,
        VectorStoreFactory vectorStoreFactory,
        IndexStatusService indexStatusService,
        KnowledgebaseConfig.GitConfig gitConfig
    ) {
        this.markdownLoaderService = markdownLoaderService;
        this.vectorStoreFactory = vectorStoreFactory;
        this.indexStatusService = indexStatusService;
        this.knowledgeDir = gitConfig.localPath();
    }

    @PostConstruct
    public void init() {
        int persistedVersion = indexStatusService.readVersion();
        collectionVersion.set(persistedVersion);
        log.info("Initialized collection version from persisted state: {}", persistedVersion);

        if (persistedVersion > 0) {
            String collectionName = "knowledge_v" + persistedVersion;
            if (vectorStoreFactory.collectionExists(collectionName)) {
                VectorStore vectorStore = vectorStoreFactory.loadVectorStore(collectionName);
                activeCollectionName.set(collectionName);
                activeVectorStore.set(vectorStore);
                log.info("Restored active collection: {}", collectionName);
            } else {
                log.warn("Persisted collection {} does not exist on disk, starting fresh", collectionName);
            }
        }
    }

    public Map<String, Object> buildAndSwapIndex() {
        if (!indexingInProgress.compareAndSet(false, true)) {
            throw new IllegalStateException("Indexing already in progress");
        }

        try {
            log.info("Starting index build from directory: {}", knowledgeDir);

            Path knowledgePath = Path.of(knowledgeDir);
            List<Chunk> chunks = markdownLoaderService.loadMarkdownFiles(knowledgePath);

            if (chunks.isEmpty()) {
                log.warn("No chunks found in knowledge directory");
            }

            int newVersion = collectionVersion.incrementAndGet();
            String newCollectionName = "knowledge_v" + newVersion;

            log.info("Creating new collection: {}", newCollectionName);
            VectorStore newVectorStore = vectorStoreFactory.createVectorStore(newCollectionName);

            if (!chunks.isEmpty()) {
                List<Document> documents = chunks.stream().map(this::chunkToDocument).toList();

                newVectorStore.add(documents);
                log.info("Indexed {} documents into {}", documents.size(), newCollectionName);
                vectorStoreFactory.saveVectorStore(newVectorStore, newCollectionName);
            }

            String oldCollectionName = activeCollectionName.getAndSet(newCollectionName);
            activeVectorStore.getAndSet(newVectorStore);

            log.info("Swapped active collection from {} to {}", oldCollectionName, newCollectionName);

            indexStatusService.writeVersion(newVersion);

            if (oldCollectionName != null) {
                deleteOldCollectionAsync(oldCollectionName);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("collectionName", newCollectionName);
            result.put("documentCount", chunks.size());
            result.put("previousCollection", oldCollectionName);
            result.put("version", newVersion);
            return result;
        } finally {
            indexingInProgress.set(false);
        }
    }

    public VectorStore getActiveVectorStore() {
        return activeVectorStore.get();
    }

    public String getActiveCollectionName() {
        return activeCollectionName.get();
    }

    public boolean isIndexing() {
        return indexingInProgress.get();
    }

    public boolean isInitialized() {
        return activeCollectionName.get() != null;
    }

    private void deleteOldCollectionAsync(String collectionName) {
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(5000);
                vectorStoreFactory.deleteCollection(collectionName);
            } catch (Exception e) {
                log.error("Error deleting old collection {}: {}", collectionName, e.getMessage());
            }
        });
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
