package dev.smithyai.orchestrator.config;

public record WorkflowConfig(String definitionsDir, Boolean repositoryWorkflows, WorkflowDefaults defaults) {
    public record WorkflowDefaults(String branchPrefix, String planApprovedLabel) {}

    public boolean repositoryWorkflowsEnabled() {
        return repositoryWorkflows == null || repositoryWorkflows;
    }
}
