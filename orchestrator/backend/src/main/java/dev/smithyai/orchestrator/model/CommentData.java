package dev.smithyai.orchestrator.model;

import java.util.Map;

public record CommentData(String user, String body, String path, int line) {
    public static CommentData conversation(String user, String body) {
        return new CommentData(user, body, "", 0);
    }

    public Map<String, Object> toMap() {
        return Map.of("user", user, "body", body, "path", path, "line", line);
    }
}
