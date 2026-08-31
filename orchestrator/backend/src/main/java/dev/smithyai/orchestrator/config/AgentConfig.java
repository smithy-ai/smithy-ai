package dev.smithyai.orchestrator.config;

import java.time.Duration;
import java.util.Optional;

public record AgentConfig(ClaudeAgentConfig claude) {
    public record ClaudeAgentConfig(
        String model,
        SecretRef oauthToken,
        SecretRef apiKey,
        String turnTimeout,
        String takeoverTimeout
    ) {
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
