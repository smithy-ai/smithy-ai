package dev.smithyai.orchestrator.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class OrchestratorConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsNamedConnectorsActorsAndSecretsFromEnvironmentAndFiles() throws Exception {
        Path webhookSecret = tempDir.resolve("webhook-secret");
        Files.writeString(webhookSecret, "hook-from-file\n");
        Path configFile = tempDir.resolve("orchestrator.yml");
        Files.writeString(configFile, config(webhookSecret));
        var environment = new MockEnvironment()
            .withProperty("ORCHESTRATOR_CONFIG", configFile.toString())
            .withProperty("CLAUDE_TOKEN", "claude-from-env")
            .withProperty("FORGEJO_SMITHY_TOKEN", "forgejo-from-env");

        var loader = new ConfigLoader(environment);
        var config = loader.orchestratorConfig();
        var registry = new ConnectorRegistry(config, environment);

        assertEquals("forgejo-main", config.defaults().vcs());
        assertEquals("hook-from-file", registry.webhookSecret("forgejo-main"));
        assertEquals("forgejo-from-env", registry.token("forgejo-main", "smithy"));
        assertEquals("smithy-bot", registry.assignee("forgejo-main", "smithy"));
        assertEquals("jira-account", registry.assignee("jira-product", "smithy"));
        assertEquals("forgejo-main", registry.defaultIssueTracker("forgejo-main"));
        assertEquals("jira-product", registry.defaultIssueTracker("jira-product"));
        assertEquals("build/test.db", loader.storageConfig().resolvedDatabase());
        assertEquals("forgejo-main", config.repositoryCatalogs().get("product").getFirst().source());
    }

    @Test
    void rejectsUnknownConfigurationFields() throws Exception {
        Path configFile = tempDir.resolve("orchestrator.yml");
        Files.writeString(configFile, config(tempDir.resolve("secret")) + "\nunknownSetting: true\n");
        Files.writeString(tempDir.resolve("secret"), "hook");
        var environment = new MockEnvironment()
            .withProperty("ORCHESTRATOR_CONFIG", configFile.toString())
            .withProperty("CLAUDE_TOKEN", "claude")
            .withProperty("FORGEJO_SMITHY_TOKEN", "forgejo");

        var error = assertThrows(java.io.UncheckedIOException.class, () -> new ConfigLoader(environment));
        assertTrue(error.getMessage().contains("parse orchestrator config"));
    }

    @Test
    void secretReferencesNeverRenderTheirLiteralValue() {
        assertEquals("SecretRef(redacted)", SecretRef.literal("do-not-print-me").toString());
    }

    private static String config(Path webhookSecret) {
        return """
        apiVersion: smithy.ai/v1alpha1
        kind: OrchestratorConfig
        storage:
          database: build/test.db
          metrics: build/metrics.jsonl
        runtime:
          docker:
            command: docker
            network: test
            taskImage: task:test
            caches: [gradle]
        agent:
          claude:
            model: test-model
            oauthToken: {env: CLAUDE_TOKEN}
        auth:
          admin:
            passwordHash: {literal: ""}
        connectors:
          forgejo-main:
            provider: forgejo
            url: http://forgejo.internal
            externalUrl: https://forgejo.example
            webhookSecret: {file: "%s"}
            actors:
              smithy:
                username: smithy-bot
                token: {env: FORGEJO_SMITHY_TOKEN}
                git: {name: Smithy, email: smithy@example.com}
          jira-product:
            provider: jira
            url: https://jira.example
            webhookSecret: {literal: jira-hook}
            actors:
              smithy:
                accountId: jira-account
                email: smithy@example.com
                apiToken: {literal: jira-token}
            issueMapping:
              repositoryField: customfield_123
              allowStoriesWithoutRepository: true
        defaults:
          vcs: forgejo-main
          issueTracker: event.source
          actor: smithy
        workflows:
          definitionsDir: /config/workflows
          repositoryWorkflows: true
          defaults:
            branchPrefix: smithy/
            planApprovedLabel: Plan Approved
        repositoryCatalogs:
          product:
            - {source: forgejo-main, owner: acme, repo: api, description: HTTP API}
        knowledgebase: {enabled: false}
        ci: {autofix: false}
        """.formatted(webhookSecret.toString());
    }
}
