package dev.smithyai.orchestrator.config;

import java.util.List;

public record RuntimeConfig(DockerRuntimeConfig docker) {
    public record DockerRuntimeConfig(String command, String network, String taskImage, List<String> caches) {
        public DockerRuntimeConfig {
            caches = caches == null ? List.of() : List.copyOf(caches);
        }
    }
}
