package dev.smithyai.orchestrator.runtime.store;

import java.util.Map;

/**
 * An execution environment a run holds — today always a Docker container.
 *
 * <p>A run may hold none, one or several. A coordinator that only reads the VCS
 * and spawns children needs no container at all, which is why the run is the
 * durable thing and this is a resource it borrows.
 */
public record RunEnvironment(String runId, String kind, String name, Map<String, Object> state) {
    public static final String CONTAINER = "container";
}
