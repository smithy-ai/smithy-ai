package dev.smithyai.orchestrator.config;

public record KnowledgebaseConfig(boolean enabled, String url, String toolName) {
    public boolean isActive() {
        return enabled && url != null && !url.isBlank();
    }

    public String mcpToolAllowName() {
        return "mcp__knowledgebase__" + toolName;
    }

    public String mcpConfigJson() {
        return "{\"mcpServers\":{\"knowledgebase\":{\"type\":\"http\",\"url\":\"%s\"}}}".formatted(url);
    }
}
