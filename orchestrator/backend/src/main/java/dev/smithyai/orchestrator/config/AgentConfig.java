package dev.smithyai.orchestrator.config;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public record AgentConfig(ClaudeAgentConfig claude) {
    private static final List<String> DEFAULT_TAKEOVER_TOOLS = List.of(
        "Read",
        "Glob",
        "Grep",
        "Bash",
        "Edit",
        "Write",
        "WebFetch"
    );

    /**
     * What a human-driven turn is told about itself. The agent otherwise
     * carries its last workflow turn's habits into the conversation — observed
     * live: a planning agent trying to call the StructuredOutput tool from a
     * takeover turn, where no schema and therefore no such tool exists, and
     * concluding it could not change the plan.
     */
    public static final String DEFAULT_TAKEOVER_SYSTEM_PROMPT = String.join(
        " ",
        "A person has taken this session over from the Smithy dashboard and is talking to you directly;",
        "they are the human in the loop for the workflow this run belongs to.",
        "Discuss, verify in the code, and refine with them.",
        "In this mode you cannot post to the issue tracker, open merge requests, or return structured output:",
        "the StructuredOutput tool and the workflow's own steps are not available here, so do not try.",
        "When they hand the session back, the workflow asks you for the final result",
        "(for a planning run: the complete plan) in a separate structured turn,",
        "so keep an explicit, up-to-date statement of what has been agreed in this conversation.",
        "If they ask for something only the workflow can do, say so and tell them to hand back."
    );

    public record ClaudeAgentConfig(
        String model,
        SecretRef oauthToken,
        SecretRef apiKey,
        String turnTimeout,
        String takeoverTimeout,
        List<String> takeoverTools,
        String takeoverSystemPrompt
    ) {
        /** The system prompt appended to a human-driven turn; the built-in text unless the deployment wrote its own. */
        public String resolvedTakeoverSystemPrompt() {
            return takeoverSystemPrompt == null || takeoverSystemPrompt.isBlank()
                ? DEFAULT_TAKEOVER_SYSTEM_PROMPT
                : takeoverSystemPrompt;
        }

        /**
         * Tools a human-driven turn may use.
         *
         * <p>Not the stage's list: a definition scopes tools per step to constrain
         * the agent when it is working on its own — read-only while planning, write
         * while building — and that does not map onto whatever a person decides to
         * ask for after taking control. So this is its own setting, and it defaults
         * to the full working set rather than to nothing.
         */
        public List<String> resolvedTakeoverTools() {
            return takeoverTools == null || takeoverTools.isEmpty() ? DEFAULT_TAKEOVER_TOOLS : takeoverTools;
        }

        /**
         * Wall-clock budget for one agent turn, or empty to keep the built-in
         * default. Accepts {@code 45m}, {@code 2h}, {@code 900s} or ISO-8601
         * ({@code PT45M}).
         */
        public Optional<Duration> resolvedTurnTimeout() {
            return parse(turnTimeout, "turnTimeout");
        }

        /**
         * Budget for a turn a human drove from the dashboard. Separate because a
         * person is waiting on the reply in a browser, so it wants to be minutes
         * rather than the tens of minutes a build turn is allowed.
         */
        public Optional<Duration> resolvedTakeoverTimeout() {
            return parse(takeoverTimeout, "takeoverTimeout");
        }

        private static Optional<Duration> parse(String raw, String key) {
            if (raw == null || raw.isBlank()) return Optional.empty();
            return Optional.of(parseTurnTimeout(raw, key));
        }

        private static Duration parseTurnTimeout(String raw, String key) {
            Duration parsed;
            try {
                String value = raw.strip();
                if (value.regionMatches(true, 0, "P", 0, 1)) {
                    parsed = Duration.parse(value);
                } else {
                    char unit = value.charAt(value.length() - 1);
                    long amount = Long.parseLong(value.substring(0, value.length() - 1).strip());
                    parsed = switch (Character.toLowerCase(unit)) {
                        case 's' -> Duration.ofSeconds(amount);
                        case 'm' -> Duration.ofMinutes(amount);
                        case 'h' -> Duration.ofHours(amount);
                        default -> throw new IllegalArgumentException("unknown unit '" + unit + "'");
                    };
                }
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                    "agent.claude.%s is not a duration: '%s' (expected e.g. 45m, 2h, 900s or PT45M)".formatted(
                        key,
                        raw
                    ),
                    e
                );
            }
            if (parsed.isZero() || parsed.isNegative()) {
                throw new IllegalStateException("agent.claude.%s must be positive, got '%s'".formatted(key, raw));
            }
            return parsed;
        }
    }
}
