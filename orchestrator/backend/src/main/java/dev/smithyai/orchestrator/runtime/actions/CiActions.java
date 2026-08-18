package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.config.CiConfig;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Deciding whether to chase a failing pipeline.
 *
 * <p>An agent that debugs CI unprompted can burn a long time on a failure that
 * has nothing to do with its change, so the decision is bounded twice: by
 * whether autofix is on at all, and by an attempt cap. Both used to live inside
 * one flow's CI handler; the counters live in run variables now, so they survive
 * the container being rebuilt.
 */
@Slf4j
@Configuration
public class CiActions {

    static final String ATTEMPTS_VAR = "ciAttempts";
    static final String PAUSED_VAR = "ciPaused";

    /**
     * Decide whether this failure should be worked on.
     *
     * <p>Reports a reason rather than acting, so the definition owns what to
     * say and where — the platform has no opinion on whether that is a PR
     * comment or an issue label.
     */
    @Bean
    public WorkflowAction ciRetryGuardAction(RunStore store, CiConfig ciConfig) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "ci.retryGuard";
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var run = context.run();
                if (asBoolean(run.vars().get(PAUSED_VAR))) {
                    return verdict(false, "paused", asInt(run.vars().get(ATTEMPTS_VAR)));
                }

                boolean autofix = boolInput(input, "autofix", ciConfig.resolvedAutofix());
                if (!autofix) {
                    pause(store, run.id(), asInt(run.vars().get(ATTEMPTS_VAR)));
                    return verdict(false, "autofix-disabled", asInt(run.vars().get(ATTEMPTS_VAR)));
                }

                int attempts = asInt(run.vars().get(ATTEMPTS_VAR)) + 1;
                int max = intInput(input, "maxAttempts", 5);
                if (attempts > max) {
                    pause(store, run.id(), attempts - 1);
                    return verdict(false, "attempts-exhausted", attempts - 1);
                }

                store.mergeVars(run.id(), Map.of(ATTEMPTS_VAR, attempts));
                return verdict(true, "ok", attempts);
            }
        };
    }

    /** Clear the counters — the pipeline went green, or a human said carry on. */
    @Bean
    public WorkflowAction ciResetAction(RunStore store) {
        return new WorkflowAction() {
            @Override
            public String type() {
                return "ci.reset";
            }

            @Override
            public boolean idempotent() {
                return true;
            }

            @Override
            public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
                var cleared = new LinkedHashMap<String, Object>();
                cleared.put(ATTEMPTS_VAR, 0);
                cleared.put(PAUSED_VAR, false);
                store.mergeVars(context.run().id(), cleared);
                return Map.of("reset", true);
            }
        };
    }

    private static void pause(RunStore store, String runId, int attempts) {
        var paused = new LinkedHashMap<String, Object>();
        paused.put(PAUSED_VAR, true);
        paused.put(ATTEMPTS_VAR, attempts);
        store.mergeVars(runId, paused);
    }

    private static Map<String, Object> verdict(boolean proceed, String reason, int attempts) {
        return Map.of("proceed", proceed, "reason", reason, "attempts", attempts);
    }

    private static int asInt(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value).strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean asBoolean(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }
}
