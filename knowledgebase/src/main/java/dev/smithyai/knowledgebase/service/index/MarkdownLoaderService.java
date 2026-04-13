package dev.smithyai.knowledgebase.service.index;

import dev.smithyai.knowledgebase.config.KnowledgebaseConfig;
import dev.smithyai.knowledgebase.model.Chunk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MarkdownLoaderService {

    private final int chunkSize;

    public MarkdownLoaderService(KnowledgebaseConfig config) {
        this.chunkSize = config.chunkSize();
    }

    public List<Chunk> loadMarkdownFiles(Path directory) {
        List<Chunk> chunks = new ArrayList<>();

        if (!Files.exists(directory)) {
            log.warn("Directory does not exist: {}", directory);
            return chunks;
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            paths
                .filter(p -> p.toString().endsWith(".md"))
                .forEach(file -> {
                    try {
                        String content = Files.readString(file);
                        List<Chunk> fileChunks = chunkMarkdownContent(content, file.toString(), chunkSize);
                        chunks.addAll(fileChunks);
                    } catch (IOException e) {
                        log.error("Error reading file {}: {}", file, e.getMessage());
                    }
                });
        } catch (IOException e) {
            log.error("Error walking directory {}: {}", directory, e.getMessage());
        }

        log.info("Loaded {} markdown chunks", chunks.size());
        return chunks;
    }

    private List<Chunk> chunkMarkdownContent(String content, String filePath, int maxSize) {
        List<Chunk> chunks = new ArrayList<>();
        String[] lines = content.split("\n");

        List<String> currentChunk = new ArrayList<>();
        String currentSection = "Introduction";
        int chunkStartLine = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (line.startsWith("#")) {
                if (!currentChunk.isEmpty()) {
                    String chunkText = String.join("\n", currentChunk).trim();
                    if (!chunkText.isEmpty()) {
                        chunks.add(new Chunk(chunkText, filePath, currentSection, chunkStartLine, i - 1));
                    }
                    currentChunk.clear();
                }

                currentSection = line.replaceAll("^#+\\s*", "").trim();
                chunkStartLine = i;
            }

            currentChunk.add(line);

            String chunkContent = String.join("\n", currentChunk);
            if (chunkContent.length() > maxSize) {
                String trimmed = chunkContent.trim();
                if (!trimmed.isEmpty()) {
                    chunks.add(new Chunk(trimmed, filePath, currentSection, chunkStartLine, i));
                }
                currentChunk.clear();
                chunkStartLine = i;
            }
        }

        if (!currentChunk.isEmpty()) {
            String chunkText = String.join("\n", currentChunk).trim();
            if (!chunkText.isEmpty()) {
                chunks.add(new Chunk(chunkText, filePath, currentSection, chunkStartLine, lines.length - 1));
            }
        }

        return chunks;
    }
}
