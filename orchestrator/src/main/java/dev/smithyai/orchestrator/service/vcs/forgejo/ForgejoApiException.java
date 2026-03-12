package dev.smithyai.orchestrator.service.vcs.forgejo;

public class ForgejoApiException extends RuntimeException {

    private final int statusCode;

    public ForgejoApiException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean isNotFound() {
        return statusCode == 404;
    }
}
