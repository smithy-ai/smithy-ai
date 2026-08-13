package dev.smithyai.orchestrator.config;

/**
 * The machine identities this orchestrator answers as.
 *
 * <p>Which actor an issue is assigned to is how a human says what kind of work
 * it is: a feature handed to the coordinator, a task handed to smithy. Without
 * that distinction both workflows claim the same issue and two agents start on
 * it.
 */
public record BotConfig(BotEntry smithy, BotEntry architect, BotEntry coordinator) {
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

    public String resolvedCoordinatorUser() {
        return coordinator != null ? coordinator.resolvedUser("coordinator") : "coordinator";
    }

    public String resolvedCoordinatorEmail() {
        return coordinator != null ? coordinator.resolvedEmail("coordinator@localhost") : "coordinator@localhost";
    }

    /** Every actor an inbound event may be addressed to. */
    public java.util.List<String> actors() {
        return java.util.List.of(resolvedSmithyUser(), resolvedArchitectUser(), resolvedCoordinatorUser());
    }

    public String resolvedArchitectEmail() {
        return architect != null ? architect.resolvedEmail("architect@localhost") : "architect@localhost";
    }
}
