package dev.smithyai.orchestrator.service.claude;

public class ClaudeParseException extends RuntimeException {

    private final String rawContent;

    public ClaudeParseException(String message, String rawContent, Throwable cause) {
        super(message, cause);
        this.rawContent = rawContent;
    }

    public String getRawContent() {
        return rawContent;
    }
}
