package dev.smithyai.orchestrator.service.vcs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The repository clients this deployment can act through, by actor.
 *
 * <p>Who opens a pull request, who leaves a review and who pushes a branch are
 * separate questions from what is being done, and a reader of the repository
 * should be able to tell the reviewer from the author.
 *
 * <p>An actor with no identity of its own falls back to the default one, so a
 * single-account deployment keeps working, at the cost of everything being
 * attributed to that account.
 */
public class VcsClients {

    private final Map<String, VcsClient> byActor;
    private final String defaultActor;

    public VcsClients(Map<String, VcsClient> byActor, String defaultActor) {
        this.byActor = new LinkedHashMap<>(byActor);
        this.defaultActor = defaultActor;
    }

    /** One identity for everything, which is what a single-account deployment has. */
    public VcsClients(VcsClient only) {
        this(Map.of("smithy", only), "smithy");
    }

    /** @param actor who to act as; unknown or blank means the default */
    public VcsClient forActor(String actor) {
        var client = actor == null || actor.isBlank() ? null : byActor.get(actor);
        return client != null ? client : byActor.get(defaultActor);
    }

    public Set<String> actors() {
        return byActor.keySet();
    }
}
