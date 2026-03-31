package dev.smithyai.knowledgebase.service.index;

import dev.smithyai.knowledgebase.config.KnowledgebaseConfig;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class VectorStoreFactory {

    private final EmbeddingModel embeddingModel;
    private final Path persistenceDir;

    public VectorStoreFactory(EmbeddingModel embeddingModel, KnowledgebaseConfig.VectorstoreConfig vectorstoreConfig) {
        this.embeddingModel = embeddingModel;
        this.persistenceDir = Path.of(vectorstoreConfig.path());

        try {
            Files.createDirectories(persistenceDir);
        } catch (IOException e) {
            log.error("Failed to create vectorstore directory: {}", vectorstoreConfig.path(), e);
        }

        log.info("VectorStoreFactory initialized with persistence path: {}", vectorstoreConfig.path());
    }

    public VectorStore createVectorStore(String collectionName) {
        VectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        log.info("Created SimpleVectorStore for collection: {}", collectionName);
        return vectorStore;
    }

    public void saveVectorStore(VectorStore vectorStore, String collectionName) {
        File file = getCollectionFile(collectionName);
        ((SimpleVectorStore) vectorStore).save(file);
        log.info("Saved VectorStore to: {}", file.getAbsolutePath());
    }

    public VectorStore loadVectorStore(String collectionName) {
        File file = getCollectionFile(collectionName);
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        vectorStore.load(file);
        log.info("Loaded VectorStore from: {}", file.getAbsolutePath());
        return vectorStore;
    }

    public void deleteCollection(String collectionName) {
        try {
            File file = getCollectionFile(collectionName);
            if (file.delete()) {
                log.info("Deleted collection file: {}", file.getAbsolutePath());
            } else {
                log.warn("Collection file did not exist or could not be deleted: {}", file.getAbsolutePath());
            }
        } catch (Exception e) {
            log.error("Failed to delete collection {}: {}", collectionName, e.getMessage());
        }
    }

    public boolean collectionExists(String collectionName) {
        return getCollectionFile(collectionName).exists();
    }

    private File getCollectionFile(String collectionName) {
        return persistenceDir.resolve(collectionName + ".json").toFile();
    }
}
