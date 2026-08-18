package dev.smithyai.orchestrator.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record OrchestratorConfig(
    String apiVersion,
    String kind,
    StorageConfig storage,
    RuntimeConfig runtime,
    AgentConfig agent,
    AuthConfig auth,
    Map<String, ConnectorConfig> connectors,
    DefaultsConfig defaults,
    WorkflowConfig workflows,
    Map<String, List<RepositoryCatalogConfig>> repositoryCatalogs,
    KnowledgebaseConfig knowledgebase,
    CiConfig ci
) {
    public static final String API_VERSION = "smithy.ai/v1alpha1";
    public static final String KIND = "OrchestratorConfig";

    public OrchestratorConfig {
        connectors = connectors == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(connectors));
        if (repositoryCatalogs == null) {
            repositoryCatalogs = Map.of();
        } else {
            var copy = new LinkedHashMap<String, List<RepositoryCatalogConfig>>();
            repositoryCatalogs.forEach((name, entries) ->
                copy.put(name, entries == null ? List.of() : List.copyOf(entries))
            );
            repositoryCatalogs = Map.copyOf(copy);
        }
    }
}
