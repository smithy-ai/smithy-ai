package dev.smithyai.knowledgebase.service.mcp;

import dev.smithyai.knowledgebase.model.SearchResult;
import dev.smithyai.knowledgebase.service.index.VectorDbService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
        String repoName = getRepoFromRequest();
        log.debug("searchKnowledge called for repo={}, task={}", repoName, taskDescription);

        if (repoName == null || repoName.isBlank()) {
            return "No repository specified. The MCP URL must include a ?repo= parameter.";
        }

        try {
            if (!vectorDbService.isInitialized(repoName)) {
                log.warn("Knowledge base not yet initialized for repo {}", repoName);
                return "Knowledge base for repository '" + repoName + "' is not yet initialized.";
            }

            List<SearchResult> results = vectorDbService.search(repoName, taskDescription, 5);

            if (results.isEmpty()) {
                return "No relevant documentation found for the given task in repository '" + repoName + "'.";
            }

            return summarizerService.summarize(repoName, taskDescription, results);
        } catch (Exception e) {
            log.error("Error in searchKnowledge for repo {}: {}", repoName, e.getMessage(), e);
            return "An error occurred while searching the knowledge base. Please try again.";
        }
    }

    private String getRepoFromRequest() {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                return request.getParameter("repo");
            }
        } catch (Exception e) {
            log.warn("Could not read repo from request context: {}", e.getMessage());
        }
        return null;
    }
}
