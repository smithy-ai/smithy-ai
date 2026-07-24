package dev.smithyai.orchestrator.service.docker;

import static org.junit.jupiter.api.Assertions.*;

import dev.smithyai.orchestrator.config.BotConfig;
import dev.smithyai.orchestrator.config.ClaudeConfig;
import dev.smithyai.orchestrator.config.DockerConfig;
import dev.smithyai.orchestrator.config.RuntimeConfig.KubernetesRuntimeConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig;
import dev.smithyai.orchestrator.config.VcsProviderConfig.ForgejoProviderConfig;
import dev.smithyai.orchestrator.service.docker.dto.ContainerConfig;
import dev.smithyai.orchestrator.service.docker.dto.WorkflowType;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KubernetesContainerRuntime} against the Fabric8 CRUD mock server. Exec and
 * log streaming require a live container and are not exercised here; Pod construction and
 * label-based discovery/cleanup are.
 */
@EnableKubernetesMockClient(crud = true)
class KubernetesContainerRuntimeTest {

    // Injected by the Fabric8 mock extension.
    static KubernetesClient client;

    private static final String NS = "smithy";

    private KubernetesContainerRuntime runtime() {
        var k8s = new KubernetesRuntimeConfig(NS, null, null, "250m", "512Mi", null, null, false, null);
        var docker = new DockerConfig("docker", "forgejo-net", "ghcr.io/smithy-ai/claude-task-default:dev", "pnpm,npm");
        var claude = new ClaudeConfig("oauth-abc", null);
        var vcs = new VcsProviderConfig(
            "forgejo",
            null,
            new ForgejoProviderConfig("http://forgejo:3000", null, "wh", "smithy-token", null),
            null,
            null
        );
        var bots = new BotConfig(new BotConfig.BotEntry("smithy", "smithy@example.com"), null);
        return new KubernetesContainerRuntime(client, k8s, docker, claude, vcs, bots);
    }

    private ContainerConfig taskConfig() {
        Map<String, String> cache = new LinkedHashMap<>();
        cache.put("cache-pnpm", "/root/.local/share/pnpm/store");
        return ContainerConfig.builder()
            .cloneUrl("http://forgejo:3000/owner/repo.git")
            .branch("smithy/42-feature")
            .cacheVolumes(cache)
            .workflowType(WorkflowType.SMITHY)
            .build();
    }

    @Test
    void buildPodSetsImageLabelsEnvAndSpec() {
        Pod pod = runtime().buildPod("smithy-42", taskConfig());

        assertEquals("smithy-42", pod.getMetadata().getName());
        assertEquals(NS, pod.getMetadata().getNamespace());
        assertEquals("true", pod.getMetadata().getLabels().get("smithy.managed"));
        assertEquals("smithy", pod.getMetadata().getLabels().get("smithy.workflow"));

        var spec = pod.getSpec();
        assertEquals("Never", spec.getRestartPolicy());
        assertFalse(spec.getAutomountServiceAccountToken());

        var container = spec.getContainers().get(0);
        assertEquals("ghcr.io/smithy-ai/claude-task-default:dev", container.getImage());
        assertEquals(List.of("smithy-init"), container.getCommand());

        Map<String, String> env = new LinkedHashMap<>();
        container.getEnv().forEach(e -> env.put(e.getName(), e.getValue()));
        assertEquals("oauth-abc", env.get("CLAUDE_CODE_OAUTH_TOKEN"));
        assertEquals("http://forgejo:3000", env.get("VCS_URL"));
        assertEquals("smithy-token", env.get("VCS_TOKEN"));
        assertEquals("http://forgejo:3000/owner/repo.git", env.get("CLONE_URL"));
        assertEquals("smithy/42-feature", env.get("BRANCH"));

        // Cache volume mounted with a DNS-1123-safe name.
        assertEquals(1, container.getVolumeMounts().size());
        assertEquals("/root/.local/share/pnpm/store", container.getVolumeMounts().get(0).getMountPath());
        assertEquals("cache-pnpm", container.getVolumeMounts().get(0).getName());
        assertNotNull(spec.getVolumes().get(0).getEmptyDir());
    }

    @Test
    void discoversAndDestroysManagedPods() {
        var runtime = runtime();

        // A running managed Pod should be discoverable; an unmanaged one should not.
        client.pods().inNamespace(NS).resource(managedRunningPod("task-a")).create();
        client.pods().inNamespace(NS).resource(unmanagedPod("other")).create();

        assertTrue(runtime.containerExists("task-a"));
        assertTrue(runtime.isManagedContainer("task-a"));
        assertFalse(runtime.isManagedContainer("other"));
        assertEquals(List.of("task-a"), runtime.listManagedContainers());

        runtime.destroy("task-a");
        assertFalse(runtime.containerExists("task-a"));
        assertTrue(runtime.listManagedContainers().isEmpty());
    }

    private static Pod managedRunningPod(String name) {
        return new PodBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(NS)
            .addToLabels("smithy.managed", "true")
            .endMetadata()
            .withNewStatus()
            .withPhase("Running")
            .endStatus()
            .build();
    }

    private static Pod unmanagedPod(String name) {
        return new PodBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(NS)
            .endMetadata()
            .withNewStatus()
            .withPhase("Running")
            .endStatus()
            .build();
    }
}
