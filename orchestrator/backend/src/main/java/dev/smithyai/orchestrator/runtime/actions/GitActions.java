package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.runtime.env.RunEnvironments;
import dev.smithyai.orchestrator.service.docker.dto.ExecResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Git actions, run inside the run's container.
 *
 * <p>Pushing is the one with real behaviour behind it: a push that loses a race
 * with a human commit is common enough that failing the whole transition on it
 * would be wrong, so the agent is asked to reconcile and the push retried.
 */
@Slf4j
@Configuration
public class GitActions {

    private static final String PUSH_FIX_PROMPT = """
        The `git push` command failed with the following error:

        ```
        %s
        ```

        Fix the issue (e.g. pull --rebase, resolve conflicts) and make sure \
        all changes are committed. Do NOT push — I will push after you finish.\
        """;

    /** Fast-forward the working tree — what a human push into the branch requires. */
    @Bean
    public WorkflowAction gitPullAction(RunEnvironments environments) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "git.pull";
            }

            @Override
            public Set<Capability> requires() {
                return Set.of(Capability.ENVIRONMENT);
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var session = environments.container(context.run());
                var result = optional(input, "strategy", "rebase").equals("rebase")
                    ? session.exec("git", "pull", "--rebase")
                    : session.exec("git", "pull");
                return outcome(result);
            }
        };
    }

    /**
     * Push, and if that fails, let the agent reconcile and try once more.
     *
     * <p>Not idempotent in the replay sense: a second push after a successful
     * one is harmless, but the agent turn in the middle is not, so a resumed
     * transition reuses the recorded outcome.
     */
    @Bean
    public WorkflowAction gitPushAction(RunEnvironments environments) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "git.push";
            }

            @Override
            public Set<Capability> requires() {
                return Set.of(Capability.ENVIRONMENT);
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var session = environments.container(context.run());
                var first = session.exec("git", "push");
                if (first.exitCode() == 0) return Map.of("pushed", true, "retried", false);

                String error = first.stderr().isBlank() ? first.stdout() : first.stderr();
                log.warn("git push failed in {}: {}", session.getContainerName(), error);

                try {
                    var agent = environments.agent(context.run(), listInput(input, "tools"));
                    agent.send(PUSH_FIX_PROMPT.formatted(error));
                    agent.ensureCommitted();
                    environments.rememberAgentSession(session, agent);
                } catch (RuntimeException e) {
                    log.error("Agent could not reconcile the push in {}", session.getContainerName(), e);
                }

                var retry = session.exec("git", "push");
                boolean pushed = retry.exitCode() == 0;
                if (!pushed) {
                    log.error("git push failed on retry in {}", session.getContainerName());
                }
                // Reported rather than thrown: the caller decides whether an
                // unpushed branch is fatal or something to tell the reviewer.
                return Map.of("pushed", pushed, "retried", true, "error", pushed ? "" : error);
            }
        };
    }

    /** Whether the working tree has anything uncommitted — a guard for a following step's {@code if:}. */
    @Bean
    public WorkflowAction gitStatusAction(RunEnvironments environments) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "git.status";
            }

            @Override
            public Set<Capability> requires() {
                return Set.of(Capability.ENVIRONMENT);
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var session = environments.container(context.run());
                String porcelain = session.exec("sh", "-c", "git status --porcelain").stdout().strip();
                String branch = session.exec("sh", "-c", "git rev-parse --abbrev-ref HEAD").stdout().strip();
                return Map.of("clean", porcelain.isEmpty(), "branch", branch, "changes", porcelain);
            }
        };
    }

    /**
     * Run a command in the container.
     *
     * <p>The escape hatch, and deliberately an awkward one. A definition that
     * reaches for this repeatedly is asking for an action that does not exist
     * yet; that is the signal to add one rather than to grow the definition into
     * a shell script.
     */
    @Bean
    public WorkflowAction execAction(RunEnvironments environments) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "exec";
            }

            @Override
            public Set<Capability> requires() {
                return Set.of(Capability.ENVIRONMENT);
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var session = environments.container(context.run());
                List<String> command = listInput(input, "command");
                // Values go in as environment rather than interpolated into the
                // command, so a path with a space in it cannot become two words.
                var env = new java.util.LinkedHashMap<String, String>();
                if (input.get("env") instanceof Map<?, ?> declared) {
                    declared.forEach((key, value) -> env.put(String.valueOf(key), String.valueOf(value)));
                }
                var result = command.isEmpty()
                    ? session.exec(List.of("sh", "-c", required(input, "shell")), env)
                    : session.exec(command, env);
                if (result.exitCode() != 0 && boolInput(input, "failOnError", true)) {
                    throw new IllegalStateException(
                        "exec failed (%d) in %s: %s".formatted(
                            result.exitCode(),
                            session.getContainerName(),
                            result.stderr()
                        )
                    );
                }
                return outcome(result);
            }
        };
    }

    /** Ask the agent to commit anything it left in the working tree. */
    @Bean
    public WorkflowAction agentEnsureCommittedAction(RunEnvironments environments) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "agent.ensureCommitted";
            }

            @Override
            public Set<Capability> requires() {
                return Set.of(Capability.ENVIRONMENT, Capability.AGENT);
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var session = environments.container(context.run());
                String before = session.exec("sh", "-c", "git status --porcelain").stdout().strip();
                if (before.isEmpty()) return Map.of("committed", false);

                var agent = environments.agent(context.run(), listInput(input, "tools"));
                agent.ensureCommitted();
                environments.rememberAgentSession(session, agent);
                return Map.of("committed", true);
            }
        };
    }

    /** Release the run's container. Its history stays in the run store. */
    @Bean
    public WorkflowAction instanceDestroyAction(RunEnvironments environments) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "instance.destroy";
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                environments.destroyContainer(context.run());
                return Map.of("destroyed", true);
            }
        };
    }

    private static Map<String, Object> outcome(ExecResult result) {
        return Map.of(
            "exitCode",
            result.exitCode(),
            "ok",
            result.exitCode() == 0,
            "stdout",
            result.stdout() == null ? "" : result.stdout().strip(),
            "stderr",
            result.stderr() == null ? "" : result.stderr().strip()
        );
    }
}
