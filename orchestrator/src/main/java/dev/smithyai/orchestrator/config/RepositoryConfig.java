package dev.smithyai.orchestrator.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import dev.smithyai.orchestrator.workflow.shared.utils.Naming;

public record RepositoryConfig(ContextConfig context) {
    public static final String PATH = ".smithy/config.yml";

    public static RepositoryConfig empty() {
        return new RepositoryConfig(null);
    }

    public ContextRepository contextRepository(String sourceOwner, String sourceRepo) {
        if (context == null || context.repository == null || context.repository.isBlank()) {
            return new ContextRepository(sourceOwner, Naming.contextRepoName(sourceRepo));
        }

        String configured = context.repository.strip();
        if (configured.contains("/")) {
            String[] parts = configured.split("/", 2);
            return new ContextRepository(parts[0], parts[1]);
        }

        String owner = context.owner != null && !context.owner.isBlank() ? context.owner.strip() : sourceOwner;
        return new ContextRepository(owner, configured);
    }

    public record ContextConfig(String owner, @JsonAlias("repo") String repository) {}
}
