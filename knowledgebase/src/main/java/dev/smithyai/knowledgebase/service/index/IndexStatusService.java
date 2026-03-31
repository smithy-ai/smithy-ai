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
    private static final String VERSION_KEY = "version";

    private final String gitLocalPath;

    public IndexStatusService(KnowledgebaseConfig.GitConfig gitConfig) {
        this.gitLocalPath = gitConfig.localPath();
    }

    public int readVersion() {
        Path statusFile = getStatusFilePath();

        if (!Files.exists(statusFile)) {
            log.info("No .index-status file found, starting from version 0");
            return 0;
        }

        try {
            Properties props = new Properties();
            props.load(Files.newBufferedReader(statusFile));

            String versionStr = props.getProperty(VERSION_KEY);
            if (versionStr == null) {
                return 0;
            }

            int version = Integer.parseInt(versionStr.trim());
            log.info("Read version {} from .index-status file", version);
            return version;
        } catch (NumberFormatException | IOException e) {
            log.warn("Error reading .index-status file, starting from version 0: {}", e.getMessage());
            return 0;
        }
    }

    public void writeVersion(int version) {
        Path statusFile = getStatusFilePath();

        try {
            Files.createDirectories(statusFile.getParent());
            Properties props = new Properties();
            props.setProperty(VERSION_KEY, String.valueOf(version));
            props.store(Files.newBufferedWriter(statusFile), "Index status - do not edit manually");
            log.info("Persisted version {} to .index-status file", version);
        } catch (IOException e) {
            log.error("Failed to write .index-status file: {}", e.getMessage());
        }
    }

    private Path getStatusFilePath() {
        return Path.of(gitLocalPath).resolve(STATUS_FILE_NAME);
    }
}
