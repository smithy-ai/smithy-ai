package dev.smithyai.knowledgebase.service.index;

import static org.assertj.core.api.Assertions.assertThat;

import dev.smithyai.knowledgebase.config.KnowledgebaseConfig.VectorstoreConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexStatusServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsDefaultsWhenStatusFileDoesNotExist() {
        IndexStatusService service = new IndexStatusService(new VectorstoreConfig(tempDir.toString()));

        assertThat(service.readVersion("owner--repo")).isZero();
        assertThat(service.readCommit("owner--repo")).isNull();
    }

    @Test
    void writesAndReadsVersionAndCommit() {
        IndexStatusService service = new IndexStatusService(new VectorstoreConfig(tempDir.toString()));

        service.writeStatus("owner--repo", 3, "abc123");

        assertThat(service.readVersion("owner--repo")).isEqualTo(3);
        assertThat(service.readCommit("owner--repo")).isEqualTo("abc123");
    }

    @Test
    void supportsExistingVersionOnlyStatusFiles() throws Exception {
        Files.writeString(tempDir.resolve(".index-status"), "owner--repo.version=7\n");
        IndexStatusService service = new IndexStatusService(new VectorstoreConfig(tempDir.toString()));

        assertThat(service.readVersion("owner--repo")).isEqualTo(7);
        assertThat(service.readCommit("owner--repo")).isNull();
    }
}
