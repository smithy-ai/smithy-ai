package dev.smithyai.knowledgebase.service.index;

import dev.smithyai.knowledgebase.config.KnowledgebaseConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class IndexStatusService {

    private static final String STATUS_FILE_NAME = ".index-status";

    private final Path statusFilePath;

    public IndexStatusService(KnowledgebaseConfig.VectorstoreConfig vectorstoreConfig) {
        this.statusFilePath = Path.of(vectorstoreConfig.path()).resolve(STATUS_FILE_NAME);
    }

    public int readVersion(String repoName) {
        if (!Files.exists(statusFilePath)) {
            return 0;
        }

        try {
            Properties props = new Properties();
            props.load(Files.newBufferedReader(statusFilePath));

            String versionStr = props.getProperty(repoName + ".version");
            if (versionStr == null) return 0;

            return Integer.parseInt(versionStr.trim());
        } catch (NumberFormatException | IOException e) {
            log.warn("Error reading version for {}: {}", repoName, e.getMessage());
            return 0;
        }
    }

    public void writeVersion(String repoName, int version) {
        try {
            Files.createDirectories(statusFilePath.getParent());

            Properties props = new Properties();
            if (Files.exists(statusFilePath)) {
                props.load(Files.newBufferedReader(statusFilePath));
            }

            props.setProperty(repoName + ".version", String.valueOf(version));
            props.store(Files.newBufferedWriter(statusFilePath), "Index status - do not edit manually");
            log.info("Persisted version {} for repo {}", version, repoName);
        } catch (IOException e) {
            log.error("Failed to write .index-status: {}", e.getMessage());
        }
    }
}
