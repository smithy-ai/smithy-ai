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
        return java.util.List.copyOf(actorUsers().values());
    }

    public java.util.Map<String, String> actorUsers() {
        var actors = new java.util.LinkedHashMap<String, String>();
        if (smithy != null) actors.put(VcsProviderConfig.SMITHY, resolvedSmithyUser());
        if (architect != null) actors.put(VcsProviderConfig.ARCHITECT, resolvedArchitectUser());
        if (coordinator != null) actors.put(VcsProviderConfig.COORDINATOR, resolvedCoordinatorUser());
        return java.util.Map.copyOf(actors);
    }

    public java.util.List<String> actorEmails() {
        var emails = new java.util.ArrayList<String>();
        if (smithy != null) emails.add(resolvedSmithyEmail());
        if (architect != null) emails.add(resolvedArchitectEmail());
        if (coordinator != null) emails.add(resolvedCoordinatorEmail());
        return java.util.List.copyOf(emails);
    }

    public String resolvedArchitectEmail() {
        return architect != null ? architect.resolvedEmail("architect@localhost") : "architect@localhost";
    }
}
