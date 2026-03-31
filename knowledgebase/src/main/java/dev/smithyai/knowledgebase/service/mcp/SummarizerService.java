package dev.smithyai.knowledgebase.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.knowledgebase.model.SearchResult;
import dev.smithyai.knowledgebase.service.index.VectorDbService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SummarizerService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatClient chatClient;
    private final VectorDbService vectorDbService;

    private static final ThreadLocal<String> currentRepoName = new ThreadLocal<>();

    // @formatter:off
    private static final String SYSTEM_PROMPT = """
        You are a coding standards and guidelines assistant. Your role is to provide comprehensive overviews
        of project instructions, coding standards, best practices, and guidelines relevant to an agent's coding task.

        When given context chunks from project documentation, create a thorough and organized summary.
        DO NOT ADD EXTRA INFORMATION THAT WAS NOT PRESENT IN THE RETRIEVED DOCUMENTATION CONTEXT.

        If the provided context seems incomplete or you need clarification on specific topics, use the
        retrieve_additional_context tool to search for more relevant information. Continue searching until you have a
        comprehensive understanding of all applicable guidelines. It is important to use this tool, otherwise you will
        only receive the context based on the users exact description of the task.

        Keep the response clear and to the point. It will be used by a coding agent to ensure their changes are aligned with
        this project's best practices. Do not add information to the summary that was not found in the context.""";
    // @formatter:on

    public SummarizerService(ChatClient.Builder chatClientBuilder, VectorDbService vectorDbService) {
        this.chatClient = chatClientBuilder.build();
        this.vectorDbService = vectorDbService;
    }

    public String summarize(String repoName, String taskDescription, List<SearchResult> initialResults) {
        String context = formatSearchResults(initialResults);

        String userMessage =
            """
            I need help with the following task:

            %s

            Here is relevant context from our project documentation. The score field indicates the relevance of that part,
            higher values indicate more relevant information, low values can be ignored if not relevant to the question:

            %s

            Please provide a comprehensive overview of the documentation context that applies to this task.
            If you need additional context, use the retrieve_additional_context tool to search for more information."""
                .formatted(taskDescription, context);

        try {
            currentRepoName.set(repoName);
            String response = chatClient.prompt().system(SYSTEM_PROMPT).user(userMessage).tools(this).call().content();

            log.debug("Generated summary for repo={}, task={}", repoName, taskDescription);
            return response;
        } catch (Exception e) {
            log.error("Error generating summary: {}", e.getMessage());
            throw new RuntimeException("Failed to generate summary", e);
        } finally {
            currentRepoName.remove();
        }
    }

    private String formatSearchResults(List<SearchResult> results) {
        if (results.isEmpty()) {
            return "No context found in documentation.";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            if (i > 0) {
                sb.append("\n\n---\n\n");
            }
            sb.append("**File:** ")
                .append(result.chunk().filePath())
                .append("\n")
                .append("**Section:** ")
                .append(result.chunk().section())
                .append("\n")
                .append("**Score:** ")
                .append(String.format("%.2f", result.score()))
                .append("\n\n")
                .append(result.chunk().content());
        }
        return sb.toString();
    }

    @Tool(description = "Retrieve additional context based on a search query")
    public String retrieveAdditionalContext(@ToolParam(description = "Text to search") String query) {
        String repoName = currentRepoName.get();
        if (repoName == null) {
            return "{\"success\": false, \"error\": \"No repository context\"}";
        }

        try {
            List<SearchResult> results = vectorDbService.search(repoName, query, 3);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("results_count", results.size());
            response.put("content", formatSearchResults(results));

            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("Error retrieving additional context: {}", e.getMessage());
            try {
                return objectMapper.writeValueAsString(Map.of("success", false, "error", e.getMessage()));
            } catch (Exception ex) {
                return "{\"success\": false}";
            }
        }
    }
}
