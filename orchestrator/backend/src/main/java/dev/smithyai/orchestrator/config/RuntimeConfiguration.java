package dev.smithyai.orchestrator.config;

import dev.smithyai.orchestrator.service.docker.ContainerRuntime;
import dev.smithyai.orchestrator.service.docker.DockerCli;
import dev.smithyai.orchestrator.service.docker.DockerContainerRuntime;
import dev.smithyai.orchestrator.service.docker.KubernetesContainerRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires exactly one {@link ContainerRuntime} into the application context based on
 * {@code runtime.type}. The unused implementation is never instantiated, so the Kubernetes client
 * is only created when {@code runtime.type=kubernetes} and the Docker CLI is only required for the
 * Docker runtime.
 */
@Slf4j
@Configuration
public class RuntimeConfiguration {

    @Bean
    public ContainerRuntime containerRuntime(
        RuntimeConfig runtimeConfig,
        DockerConfig dockerConfig,
        ClaudeConfig claudeConfig,
        VcsProviderConfig vcsConfig,
        BotConfig botConfig,
        ObjectProvider<DockerCli> dockerCli
    ) {
        var type = runtimeConfig.resolvedType();
        log.info("Selecting container runtime: {}", type);
        return switch (type) {
            case DOCKER -> new DockerContainerRuntime(
                dockerConfig,
                claudeConfig,
                vcsConfig,
                botConfig,
                dockerCli.getObject()
            );
            case KUBERNETES -> new KubernetesContainerRuntime(
                runtimeConfig.resolvedKubernetes(),
                dockerConfig,
                claudeConfig,
                vcsConfig,
                botConfig
            );
        };
    }
}
