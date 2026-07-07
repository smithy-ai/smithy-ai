package dev.smithyai.orchestrator.service.agent;

import dev.smithyai.orchestrator.config.CodexConfig;
import dev.smithyai.orchestrator.config.KnowledgebaseConfig;
import dev.smithyai.orchestrator.service.claude.ClaudeSession;
import dev.smithyai.orchestrator.service.codex.CodexSession;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AgentSessionFactory {

    private final CodexConfig codexConfig;

    public AgentSessionFactory(CodexConfig codexConfig) {
        this.codexConfig = codexConfig;
    }

    public AgentSession create(
        ContainerSession container,
        List<String> tools,
        KnowledgebaseConfig knowledgebaseConfig
    ) {
        return create(container, tools, null, knowledgebaseConfig);
    }

    public AgentSession create(
        ContainerSession container,
        List<String> tools,
        String existingSessionId,
        KnowledgebaseConfig knowledgebaseConfig
    ) {
        if (codexConfig.enabled()) {
            return new CodexSession(container, tools, existingSessionId, knowledgebaseConfig, codexConfig);
        }
        return new ClaudeSession(container, tools, existingSessionId, knowledgebaseConfig);
    }
}
