package dev.smithyai.orchestrator.gitlab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.service.vcs.gitlab.GitLabNotePosition;
import org.junit.jupiter.api.Test;

class GitLabNotePositionTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void aNoteOnAnAddedOrKeptLineIsAtItsNewPosition() throws Exception {
        var position = json.readTree(
            """
            {"old_path": "src/Old.java", "new_path": "src/App.java", "old_line": 3, "new_line": 12}
            """
        );

        assertEquals(new GitLabNotePosition("src/App.java", 12), GitLabNotePosition.of(position));
    }

    @Test
    void aNoteOnADeletedLineIsAtItsOldPosition() throws Exception {
        var position = json.readTree(
            """
            {"old_path": "src/Gone.java", "new_path": null, "old_line": 7, "new_line": null}
            """
        );

        assertEquals(new GitLabNotePosition("src/Gone.java", 7), GitLabNotePosition.of(position));
    }

    @Test
    void aNoteWithoutAPositionIsNowhere() throws Exception {
        var note = json.readTree(
            """
            {"body": "looks good"}
            """
        );

        assertEquals(new GitLabNotePosition("", 0), GitLabNotePosition.of(note.path("position")));
    }
}
