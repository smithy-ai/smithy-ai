package dev.smithyai.knowledgebase.service.mcp;

import dev.smithyai.knowledgebase.model.SearchResult;
import dev.smithyai.knowledgebase.service.index.VectorDbService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SearchKnowledgeTool {

    private final VectorDbService vectorDbService;
    private final SummarizerService summarizerService;

    public SearchKnowledgeTool(VectorDbService vectorDbService, SummarizerService summarizerService) {
        this.vectorDbService = vectorDbService;
        this.summarizerService = summarizerService;
    }

    @McpTool(
        description =
            "IMPORTANT: Always call this tool BEFORE planning or implementing any code changes. "
                + "This tool searches the project's knowledge base for coding guidelines, best practices, "
                + "architectural decisions, and documentation that must be followed. "
                + "Failure to consult this tool may result in code that violates project standards."
    )
    public String searchKnowledge(
        @McpToolParam(description = "Description of the coding task or question you need help with")
            String taskDescription
    ) {
        log.debug("searchKnowledge called with task: {}", taskDescription);

        try {
            if (!vectorDbService.isInitialized()) {
                log.warn("Knowledge base not yet initialized");
                return "Knowledge base is not yet initialized. Please wait for the initial sync to complete.";
            }

            List<SearchResult> results = vectorDbService.search(taskDescription, 5);

            if (results.isEmpty()) {
                return "No relevant documentation found for the given task.";
            }

            return summarizerService.summarize(taskDescription, results);
        } catch (Exception e) {
            log.error("Error in searchKnowledge tool: {}", e.getMessage(), e);
            return "An error occurred while searching the knowledge base. Please try again.";
        }
    }
}
