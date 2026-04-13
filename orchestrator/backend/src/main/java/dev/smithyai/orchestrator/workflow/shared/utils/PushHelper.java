package dev.smithyai.orchestrator.workflow.shared.utils;

import dev.smithyai.orchestrator.service.claude.ClaudeSession;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import dev.smithyai.orchestrator.service.vcs.VcsClient;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class PushHelper {

    private static final String PUSH_FIX_PROMPT =
        "The `git push` command failed with the following error:\n\n" +
        "```\n%s\n```\n\n" +
        "Fix the issue (e.g. pull --rebase, resolve conflicts) and make sure " +
        "all changes are committed. Do NOT push — I will push after you finish.";

    private PushHelper() {}

    public static void pushWithRetry(
        ContainerSession session,
        ClaudeSession claude,
        VcsClient client,
        String owner,
        String repo,
        Integer prNumber
    ) {
        // First attempt
        var result = session.exec("git", "push");
        if (result.exitCode() == 0) return;

        String errorOutput = result.stderr().isBlank() ? result.stdout() : result.stderr();
        log.warn("git push failed in {}: {}", session.getContainerName(), errorOutput);

        // Ask Claude to fix
        try {
            String prompt = PUSH_FIX_PROMPT.formatted(errorOutput);
            claude.send(prompt);
            claude.ensureCommitted();
        } catch (Exception e) {
            log.error("Claude fix attempt failed in {}", session.getContainerName(), e);
        }

        // Second attempt
        var retry = session.exec("git", "push");
        if (retry.exitCode() == 0) {
            log.info("git push succeeded on retry in {}", session.getContainerName());
            return;
        }
        log.error("git push failed on retry in {}", session.getContainerName());

        // Give up — comment on PR if we have one
        if (prNumber != null) {
            try {
                client.createPrComment(owner, repo, prNumber, "Can't push to branch.");
            } catch (Exception e) {
                log.error("Failed to comment on PR #{} about push failure", prNumber, e);
            }
        }
    }
}
