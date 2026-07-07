package dev.smithyai.orchestrator.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

class RepositoryConfigTest {

    private final YAMLMapper mapper = new YAMLMapper();

    @Test
    void defaultsToContextSuffix() {
        var context = RepositoryConfig.empty().contextRepository("acme", "shop");

        assertEquals("acme", context.owner());
        assertEquals("shop-context", context.repo());
        assertEquals("acme/shop-context", context.fullName());
    }

    @Test
    void resolvesRepositoryInSourceOwner() throws Exception {
        var config = mapper.readValue(
            """
            context:
              repository: shared-guidelines
            """,
            RepositoryConfig.class
        );

        var context = config.contextRepository("acme", "shop");

        assertEquals("acme", context.owner());
        assertEquals("shared-guidelines", context.repo());
    }

    @Test
    void resolvesExplicitOwnerAndRepository() throws Exception {
        var config = mapper.readValue(
            """
            context:
              owner: platform
              repository: engineering-guidelines
            """,
            RepositoryConfig.class
        );

        var context = config.contextRepository("acme", "shop");

        assertEquals("platform", context.owner());
        assertEquals("engineering-guidelines", context.repo());
    }

    @Test
    void resolvesOwnerFromSlashQualifiedRepository() throws Exception {
        var config = mapper.readValue(
            """
            context:
              repository: platform/engineering-guidelines
            """,
            RepositoryConfig.class
        );

        var context = config.contextRepository("acme", "shop");

        assertEquals("platform", context.owner());
        assertEquals("engineering-guidelines", context.repo());
    }

    @Test
    void supportsRepoAlias() throws Exception {
        var config = mapper.readValue(
            """
            context:
              repo: engineering-guidelines
            """,
            RepositoryConfig.class
        );

        var context = config.contextRepository("acme", "shop");

        assertEquals("acme", context.owner());
        assertEquals("engineering-guidelines", context.repo());
    }
}
