package dev.smithyai.orchestrator.workflow.definition;

import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
public class WorkflowDefinitionLoader {

    public static final Path GLOBAL_WORKFLOW_DIR = Path.of("/config/workflows");
    public static final String REPOSITORY_WORKFLOW_DIR = ".smithy/workflows";

    private final WorkflowDefinitionParser parser;
    private final VcsClient vcsClient;
    private final PathMatchingResourcePatternResolver resourceResolver;

    public WorkflowDefinitionLoader(WorkflowDefinitionParser parser, @Qualifier("smithyVcs") VcsClient vcsClient) {
        this(parser, vcsClient, new PathMatchingResourcePatternResolver());
    }

    WorkflowDefinitionLoader(
        WorkflowDefinitionParser parser,
        VcsClient vcsClient,
        PathMatchingResourcePatternResolver resourceResolver
    ) {
        this.parser = parser;
        this.vcsClient = vcsClient;
        this.resourceResolver = resourceResolver;
    }

    public List<LoadedWorkflowDefinition> loadEffectiveWorkflows(RepoInfo repo) {
        var workflows = new LinkedHashMap<String, LoadedWorkflowDefinition>();
        merge(workflows, loadBuiltInWorkflows());
        merge(workflows, loadGlobalWorkflows(GLOBAL_WORKFLOW_DIR));
        merge(workflows, loadRepositoryWorkflows(repo));
        return List.copyOf(workflows.values());
    }

    public List<LoadedWorkflowDefinition> loadBuiltInWorkflows() {
        try {
            var loaded = new ArrayList<LoadedWorkflowDefinition>();
            loaded.addAll(loadResources(resourceResolver.getResources("classpath*:workflows/*.yml")));
            loaded.addAll(loadResources(resourceResolver.getResources("classpath*:workflows/*.yaml")));
            loaded.sort(Comparator.comparing(LoadedWorkflowDefinition::source));
            return loaded;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load built-in workflow definitions", e);
        }
    }

    public List<LoadedWorkflowDefinition> loadGlobalWorkflows(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        try (var stream = Files.list(directory)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> isWorkflowFile(path.getFileName().toString()))
                .sorted()
                .map(this::loadPath)
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load global workflow definitions from " + directory, e);
        }
    }

    public List<LoadedWorkflowDefinition> loadRepositoryWorkflows(RepoInfo repo) {
        var paths = vcsClient
            .listRepositoryFiles(repo.owner(), repo.repo(), REPOSITORY_WORKFLOW_DIR, null)
            .stream()
            .filter(WorkflowDefinitionLoader::isWorkflowFile)
            .sorted()
            .toList();

        var loaded = new ArrayList<LoadedWorkflowDefinition>();
        for (String path : paths) {
            var raw = vcsClient.readRepositoryFile(repo.owner(), repo.repo(), path, null);
            raw.ifPresent(yaml -> loaded.add(parse("%s/%s:%s".formatted(repo.owner(), repo.repo(), path), yaml)));
        }
        return loaded;
    }

    private void merge(
        LinkedHashMap<String, LoadedWorkflowDefinition> target,
        List<LoadedWorkflowDefinition> definitions
    ) {
        for (var loaded : definitions) {
            target.put(loaded.definition().metadata().name(), loaded);
        }
    }

    private List<LoadedWorkflowDefinition> loadResources(Resource[] resources) {
        var loaded = new ArrayList<LoadedWorkflowDefinition>();
        for (Resource resource : resources) {
            if (!isWorkflowFile(resource.getFilename())) continue;
            try {
                loaded.add(
                    parse(
                        resource.getDescription(),
                        resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                    )
                );
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to load workflow definition " + resource.getDescription(), e);
            }
        }
        return loaded;
    }

    private LoadedWorkflowDefinition loadPath(Path path) {
        try {
            return parse(path.toString(), Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load workflow definition " + path, e);
        }
    }

    private LoadedWorkflowDefinition parse(String source, String yaml) {
        return new LoadedWorkflowDefinition(source, parser.parse(source, yaml));
    }

    private static boolean isWorkflowFile(String path) {
        return path != null && (path.endsWith(".yml") || path.endsWith(".yaml"));
    }
}
