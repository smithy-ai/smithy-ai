package dev.smithyai.orchestrator.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;

public record DockerConfig(
    String command,
    String network,
    @JsonProperty("task-image") String taskImage,
    @JsonProperty("cache-volumes") String cacheVolumes,
    /** Copied into {@code /root/.claude} of every new task container; blank means nothing is. */
    @JsonProperty("claude-config-dir") String claudeConfigDir
) {
    /** The pre-existing shape: no directory to copy. */
    public DockerConfig(String command, String network, String taskImage, String cacheVolumes) {
        this(command, network, taskImage, cacheVolumes, "");
    }

    public boolean hasClaudeConfigDir() {
        return claudeConfigDir != null && !claudeConfigDir.isBlank();
    }

    private static final Map<String, CacheVolumeEntry> CACHE_VOLUME_MAP = Map.of(
        "pnpm",
        new CacheVolumeEntry("cache-pnpm", "/root/.local/share/pnpm/store"),
        "npm",
        new CacheVolumeEntry("cache-npm", "/root/.npm"),
        "maven",
        new CacheVolumeEntry("cache-maven", "/root/.m2/repository"),
        "gradle",
        new CacheVolumeEntry("cache-gradle", "/root/.gradle/caches")
    );

    public Map<String, String> getCacheVolumeMap() {
        var result = new LinkedHashMap<String, String>();
        if (cacheVolumes == null || cacheVolumes.isBlank()) return result;
        for (String name : cacheVolumes.split(",")) {
            name = name.strip();
            var entry = CACHE_VOLUME_MAP.get(name);
            if (entry != null) {
                result.put(entry.volumeName(), entry.mountPath());
            }
        }
        return result;
    }

    private record CacheVolumeEntry(String volumeName, String mountPath) {}
}
