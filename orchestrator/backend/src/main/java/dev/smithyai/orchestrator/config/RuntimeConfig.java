package dev.smithyai.orchestrator.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Selects and configures the task-container runtime. {@code type} chooses the backend
 * ({@code docker} or {@code kubernetes}); the {@code kubernetes} block carries Pod-scheduling
 * settings used only by the Kubernetes runtime.
 */
public record RuntimeConfig(String type, KubernetesRuntimeConfig kubernetes) {
    public enum Type {
        DOCKER,
        KUBERNETES,
    }

    /** Default configuration (Docker runtime) used when the config omits a {@code runtime} block. */
    public static RuntimeConfig defaults() {
        return new RuntimeConfig("docker", KubernetesRuntimeConfig.defaults());
    }

    public RuntimeConfig {
        if (kubernetes == null) kubernetes = KubernetesRuntimeConfig.defaults();
    }

    /** Parse {@code type} into the enum, failing fast with a clear message on unknown values. */
    public Type resolvedType() {
        String t = type == null || type.isBlank() ? "docker" : type.strip().toLowerCase();
        return switch (t) {
            case "docker" -> Type.DOCKER;
            case "kubernetes", "k8s" -> Type.KUBERNETES;
            default -> throw new IllegalArgumentException(
                "Unknown runtime.type '" + type + "'. Accepted values: docker, kubernetes"
            );
        };
    }

    public KubernetesRuntimeConfig resolvedKubernetes() {
        return kubernetes != null ? kubernetes : KubernetesRuntimeConfig.defaults();
    }

    /**
     * Kubernetes runtime settings. Blank optional strings are treated as unset.
     */
    public record KubernetesRuntimeConfig(
        String namespace,
        @JsonProperty("task-service-account") String taskServiceAccount,
        @JsonProperty("image-pull-secret") String imagePullSecret,
        @JsonProperty("task-cpu-request") String taskCpuRequest,
        @JsonProperty("task-memory-request") String taskMemoryRequest,
        @JsonProperty("task-cpu-limit") String taskCpuLimit,
        @JsonProperty("task-memory-limit") String taskMemoryLimit,
        @JsonProperty("cache-persistence") boolean cachePersistence,
        @JsonProperty("storage-class") String storageClass
    ) {
        public static KubernetesRuntimeConfig defaults() {
            return new KubernetesRuntimeConfig("smithy", null, null, "250m", "512Mi", null, null, false, null);
        }

        public String resolvedNamespace() {
            return namespace == null || namespace.isBlank() ? "smithy" : namespace.strip();
        }

        public boolean hasServiceAccount() {
            return taskServiceAccount != null && !taskServiceAccount.isBlank();
        }

        public boolean hasImagePullSecret() {
            return imagePullSecret != null && !imagePullSecret.isBlank();
        }

        public boolean hasStorageClass() {
            return storageClass != null && !storageClass.isBlank();
        }
    }
}
