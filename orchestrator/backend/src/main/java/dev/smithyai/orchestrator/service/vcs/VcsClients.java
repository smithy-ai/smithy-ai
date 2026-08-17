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

    private final Map<String, Map<String, VcsClient>> byActor;
    private final String defaultActor;
    private final String defaultConnector;
    private final java.util.function.BiFunction<String, String, String> usernameResolver;
    private final java.util.function.Function<String, String> externalUrlResolver;

    public VcsClients(
        Map<String, Map<String, VcsClient>> byActor,
        String defaultActor,
        String defaultConnector,
        java.util.function.BiFunction<String, String, String> usernameResolver,
        java.util.function.Function<String, String> externalUrlResolver
    ) {
        this.byActor = new LinkedHashMap<>();
        byActor.forEach((actor, connectors) -> this.byActor.put(actor, new LinkedHashMap<>(connectors)));
        this.defaultActor = defaultActor;
        this.defaultConnector = defaultConnector;
        this.usernameResolver = usernameResolver;
        this.externalUrlResolver = externalUrlResolver;
    }

    public VcsClients(Map<String, Map<String, VcsClient>> byActor, String defaultActor, String defaultConnector) {
        this(
            byActor,
            defaultActor,
            defaultConnector,
            (connector, actor) -> actor,
            connector ->
                byActor
                    .values()
                    .stream()
                    .map(m -> m.get(connector))
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .map(VcsClient::baseUrl)
                    .orElse("")
        );
    }

    public VcsClients(Map<String, VcsClient> byActor, String defaultActor) {
        this(
            byActor
                .entrySet()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> Map.of("default", e.getValue()))),
            defaultActor,
            "default"
        );
    }

    /** One identity for everything, which is what a single-account deployment has. */
    public VcsClients(VcsClient only) {
        this(Map.of("smithy", only), "smithy");
    }

    /** @param actor who to act as; unknown or blank means the default */
    public VcsClient forActor(String actor) {
        return forConnector(actor, defaultConnector);
    }

    public VcsClient forConnector(String actor, String connector) {
        String resolvedActor = actor == null || actor.isBlank() ? defaultActor : actor;
        String resolvedConnector = connector == null || connector.isBlank() ? defaultConnector : connector;
        var connectors = byActor.getOrDefault(resolvedActor, byActor.get(defaultActor));
        VcsClient client = connectors == null ? null : connectors.get(resolvedConnector);
        if (client == null && !resolvedActor.equals(defaultActor)) {
            client = byActor.getOrDefault(defaultActor, Map.of()).get(resolvedConnector);
        }
        if (client == null) {
            throw new IllegalArgumentException(
                "No VCS connector named '%s' is configured for actor '%s'".formatted(resolvedConnector, resolvedActor)
            );
        }
        return client;
    }

    public boolean hasConnector(String connector) {
        return byActor
            .values()
            .stream()
            .anyMatch(connectors -> connectors.containsKey(connector));
    }

    public String defaultConnector() {
        return defaultConnector;
    }

    public String username(String connector, String actor) {
        return usernameResolver.apply(connector, actor);
    }

    public String externalUrl(String connector) {
        return externalUrlResolver.apply(connector == null || connector.isBlank() ? defaultConnector : connector);
    }

    public Set<String> actors() {
        return byActor.keySet();
    }
}
