package dev.smithyai.orchestrator.config;

/**
 * CI handling. When autofix is off, a failed pipeline pauses the workflow
 * and asks for approval on the MR instead of debugging unprompted; each
 * approved fix attempt covers one turn.
 */
public record CiConfig(Boolean autofix) {
    public boolean resolvedAutofix() {
        return autofix != null && autofix;
    }
}
