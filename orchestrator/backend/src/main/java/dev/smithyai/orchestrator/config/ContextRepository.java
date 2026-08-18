package dev.smithyai.orchestrator.config;

public record ContextRepository(String owner, String repo) {
    public ContextRepository {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("Context repository owner is required");
        }
        if (repo == null || repo.isBlank()) {
            throw new IllegalArgumentException("Context repository name is required");
        }
    }

    public String fullName() {
        return owner + "/" + repo;
    }
}
