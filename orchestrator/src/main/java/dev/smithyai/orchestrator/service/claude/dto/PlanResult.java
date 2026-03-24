package dev.smithyai.orchestrator.service.claude.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanResult(List<String> openQuestions) {
    public PlanResult {
        if (openQuestions == null) {
            openQuestions = List.of();
        }
    }
}
