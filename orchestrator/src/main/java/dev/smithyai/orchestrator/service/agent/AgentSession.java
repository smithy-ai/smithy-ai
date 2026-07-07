package dev.smithyai.orchestrator.service.agent;

import java.util.Optional;

public interface AgentSession {
    String getSessionId();

    void setContextRepoName(String contextRepoName);

    void startPlan(String prompt);

    String send(String prompt);

    <T> T send(String prompt, Class<T> resultType);

    <T> T send(String prompt, Class<T> resultType, String model);

    void ensureCommitted();

    Optional<String> latestPlanFile();
}
