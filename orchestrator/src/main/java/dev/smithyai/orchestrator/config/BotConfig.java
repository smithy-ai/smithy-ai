package dev.smithyai.orchestrator.config;

public record BotConfig(BotEntry smithy, BotEntry architect) {
    public record BotEntry(String user) {
        public String resolvedUser(String defaultUser) {
            return user != null && !user.isBlank() ? user : defaultUser;
        }
    }

    public String resolvedSmithyUser() {
        return smithy != null ? smithy.resolvedUser("smithy") : "smithy";
    }

    public String resolvedArchitectUser() {
        return architect != null ? architect.resolvedUser("architect") : "architect";
    }
}
