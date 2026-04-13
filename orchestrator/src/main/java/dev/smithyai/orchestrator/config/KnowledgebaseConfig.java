package dev.smithyai.orchestrator.config;

public record KnowledgebaseConfig(boolean enabled, String url, String toolName) {
    public boolean isActive() {
        return enabled && url != null && !url.isBlank();
    }

    public String mcpToolAllowName() {
        return "mcp__knowledgebase";
    }

    public String mcpConfigJson(String contextRepoName) {
        String separator = url.contains("?") ? "&" : "?";
        String scopedUrl = url + separator + "repo=" + contextRepoName;
        return "{\"mcpServers\":{\"knowledgebase\":{\"type\":\"http\",\"url\":\"%s\"}}}".formatted(scopedUrl);
    }
}
