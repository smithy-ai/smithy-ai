package dev.smithyai.orchestrator.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The foreman's planning universe — same file format as the smithy-orchestrator
 * skill's repos.yml, so a manifest is portable between the two. Only projects
 * listed here may receive issues.
 */
public record ReposManifest(List<RepoEntry> repos, List<GuidelineEntry> guidelines) {
    public record RepoEntry(String project, String description, String specs) {}

    /**
     * A cross-cutting guidelines repo (e.g. a design system): cloned into the
     * planning workspace for consultation within its stated scope, but never
     * a target for issues.
     */
    public record GuidelineEntry(String project, String scope) {}

    public ReposManifest {
        if (guidelines == null) guidelines = List.of();
    }

    public static ReposManifest load(Path path) {
        try {
            var mapper = YAMLMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
            ReposManifest manifest = mapper.readValue(Files.readString(path), ReposManifest.class);
            if (manifest.repos() == null || manifest.repos().isEmpty()) {
                throw new IllegalStateException("Repos manifest " + path + " has no repos");
            }
            return manifest;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read repos manifest: " + path, e);
        }
    }

    public boolean containsProject(String project) {
        return repos.stream().anyMatch(r -> project.equals(r.project()));
    }
}
