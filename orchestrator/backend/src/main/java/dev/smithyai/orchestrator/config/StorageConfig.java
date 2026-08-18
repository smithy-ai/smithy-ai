package dev.smithyai.orchestrator.config;

public record StorageConfig(String database, String metrics) {
    public static StorageConfig defaults() {
        return new StorageConfig("/config/smithy.db", "/config/metrics.jsonl");
    }

    public String resolvedDatabase() {
        return database == null || database.isBlank() ? defaults().database() : database;
    }

    public String resolvedMetrics() {
        return metrics == null || metrics.isBlank() ? defaults().metrics() : metrics;
    }
}
