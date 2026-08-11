package dev.smithyai.orchestrator.runtime.definition;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Finds workflow definitions, in precedence order.
 *
 * <p>Built-in definitions ship on the classpath. An operator overrides one, or
 * adds their own, by dropping a file into the mounted workflow directory —
 * later sources win by name, so replacing {@code smithy-development} means
 * writing a file with that name rather than patching the jar.
 *
 * <p>Parsing a bad file does not take the others down with it. An operator's
 * typo should cost them that workflow and a clear log line, not the whole
 * orchestrator.
 */
@Slf4j
@Component
public class WorkflowDefinitionLoader {

    private static final String BUILT_IN = "classpath*:workflows/*.yml";

    private final WorkflowDefinitionParser parser;
    private final PathMatchingResourcePatternResolver resources = new PathMatchingResourcePatternResolver();

    public WorkflowDefinitionLoader(WorkflowDefinitionParser parser) {
        this.parser = parser;
    }

    /**
     * Every definition available, built-in first.
     *
     * @param overrideDir a directory of operator-supplied definitions; ignored
     *                    if it does not exist, because a deployment that only
     *                    wants the built-ins should not have to create one
     */
    public List<LoadedWorkflowDefinition> load(Path overrideDir) {
        var loaded = new ArrayList<LoadedWorkflowDefinition>();
        loaded.addAll(loadBuiltIn());
        loaded.addAll(loadFrom(overrideDir));
        return loaded;
    }

    private List<LoadedWorkflowDefinition> loadBuiltIn() {
        try {
            var found = new ArrayList<LoadedWorkflowDefinition>();
            for (var resource : resources.getResources(BUILT_IN)) {
                String name = resource.getFilename();
                try (var stream = resource.getInputStream()) {
                    String yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    parse("built-in:" + name, yaml).ifPresent(found::add);
                }
            }
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read built-in workflow definitions", e);
        }
    }

    private List<LoadedWorkflowDefinition> loadFrom(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) return List.of();
        try (Stream<Path> files = Files.list(directory)) {
            return files
                .filter(path -> path.toString().endsWith(".yml") || path.toString().endsWith(".yaml"))
                .sorted(Comparator.comparing(Path::getFileName))
                .flatMap(path -> parse(path.toString(), readOrSkip(path)).stream())
                .toList();
        } catch (IOException e) {
            log.error("Failed to list workflow directory {}", directory, e);
            return List.of();
        }
    }

    private String readOrSkip(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            log.error("Failed to read workflow definition {}", path, e);
            return null;
        }
    }

    private java.util.Optional<LoadedWorkflowDefinition> parse(String source, String yaml) {
        if (yaml == null) return java.util.Optional.empty();
        try {
            var definition = parser.parse(source, yaml);
            log.info("Loaded workflow '{}' from {}", definition.metadata().name(), source);
            return java.util.Optional.of(new LoadedWorkflowDefinition(source, definition));
        } catch (WorkflowDefinitionException e) {
            log.error("Ignoring workflow definition {}: {}", source, e.getMessage());
            return java.util.Optional.empty();
        }
    }
}
