package dev.smithyai.orchestrator.config;

import java.util.List;

public record RuntimeConfig(DockerRuntimeConfig docker) {
    /**
     * @param claudeConfigDir a directory laid out like {@code ~/.claude} — e.g.
     *                        {@code skills/} and {@code agents/} — copied into
     *                        every task container at creation, so agents find
     *                        the same skills and subagents in every run
     */
    public record DockerRuntimeConfig(
        String command,
        String network,
        String taskImage,
        List<String> caches,
        String claudeConfigDir
    ) {
        public DockerRuntimeConfig {
            caches = caches == null ? List.of() : List.copyOf(caches);
        }
    }
}
