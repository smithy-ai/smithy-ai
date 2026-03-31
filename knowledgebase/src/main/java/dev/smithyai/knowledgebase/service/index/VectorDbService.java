package dev.smithyai.knowledgebase.service.index;

import dev.smithyai.knowledgebase.model.Chunk;
import dev.smithyai.knowledgebase.model.SearchResult;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class VectorDbService {

    private final IndexingService indexingService;

    public VectorDbService(IndexingService indexingService) {
        this.indexingService = indexingService;
    }

    public List<SearchResult> search(String repoName, String query, int limit) {
        VectorStore vectorStore = indexingService.getActiveVectorStore(repoName);

        if (vectorStore == null) {
            log.warn("No active index for repo {}", repoName);
            return List.of();
        }

        try {
            SearchRequest searchRequest = SearchRequest.builder().query(query).topK(limit).build();
            List<Document> results = vectorStore.similaritySearch(searchRequest);

            log.debug(
                "Search in {} for '{}' returned {} results",
                repoName,
                query,
                results.size()
            );

            return results.stream().map(this::documentToSearchResult).toList();
        } catch (Exception e) {
            log.error("Error searching repo {}: {}", repoName, e.getMessage());
            return List.of();
        }
    }

    public boolean isInitialized(String repoName) {
        return indexingService.isInitialized(repoName);
    }

    private SearchResult documentToSearchResult(Document document) {
        Chunk chunk = documentToChunk(document);
        double score = document.getScore() != null ? document.getScore() : 0.0;
        return new SearchResult(chunk, score);
    }

    private Chunk documentToChunk(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new Chunk(
            document.getText(),
            getMetadataString(metadata, "filePath"),
            getMetadataString(metadata, "section"),
            getMetadataInt(metadata, "startLine"),
            getMetadataInt(metadata, "endLine")
        );
    }

    private String getMetadataString(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value != null ? value.toString() : "";
    }

    private int getMetadataInt(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }
}
