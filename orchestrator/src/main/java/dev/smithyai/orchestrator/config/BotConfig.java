package dev.smithyai.orchestrator.config;

public record BotConfig(BotEntry smithy, BotEntry architect) {
    public record BotEntry(String user, String email) {
        public String resolvedUser(String defaultUser) {
            return user != null && !user.isBlank() ? user : defaultUser;
        }

        public String resolvedEmail(String defaultEmail) {
            return email != null && !email.isBlank() ? email : defaultEmail;
        }
    }

    public String resolvedSmithyUser() {
        return smithy != null ? smithy.resolvedUser("smithy") : "smithy";
    }

    public String resolvedArchitectUser() {
        return architect != null ? architect.resolvedUser("architect") : "architect";
    }

    public String resolvedSmithyEmail() {
        return smithy != null ? smithy.resolvedEmail("smithy@localhost") : "smithy@localhost";
    }

    public String resolvedArchitectEmail() {
        return architect != null ? architect.resolvedEmail("architect@localhost") : "architect@localhost";
    }
}
