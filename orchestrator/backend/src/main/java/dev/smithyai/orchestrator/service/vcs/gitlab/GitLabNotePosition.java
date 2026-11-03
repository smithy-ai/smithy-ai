package dev.smithyai.orchestrator.service.vcs.gitlab;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Where a GitLab diff note sits: its file and line.
 *
 * <p>A note on an added or kept line has a new path and line. A note on a
 * deleted line has only the old ones, so they are the fallback. A note without
 * a position is at {@code ("", 0)}.
 */
public record GitLabNotePosition(String path, long line) {
    public static GitLabNotePosition of(JsonNode position) {
        String path = position.path("new_path").asText("");
        if (path.isEmpty()) path = position.path("old_path").asText("");
        long line = position.path("new_line").asLong(0);
        if (line == 0) line = position.path("old_line").asLong(0);
        return new GitLabNotePosition(path, line);
    }
}
