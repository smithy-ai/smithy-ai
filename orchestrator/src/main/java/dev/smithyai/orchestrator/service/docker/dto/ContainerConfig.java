package dev.smithyai.orchestrator.service.docker.dto;

import java.util.List;
import java.util.Map;
import lombok.Builder;

@Builder
public record ContainerConfig(
    String cloneUrl,
    String branch,
    String sourceBranch,
    Map<String, String> cacheVolumes,
    String gitEmail,
    String gitUsername,
    String vcsToken,
    List<ExtraRepo> extraRepos,
    WorkflowType workflowType
) {
    public ContainerConfig {
        if (extraRepos == null) extraRepos = List.of();
    }

    public record ExtraRepo(String cloneUrl, String path, String branch) {}
}
