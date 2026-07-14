package dev.smithyai.orchestrator.service.claude.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewResult(String summary, List<ReviewComment> comments) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReviewComment(String path, int line, String body) {}
}
