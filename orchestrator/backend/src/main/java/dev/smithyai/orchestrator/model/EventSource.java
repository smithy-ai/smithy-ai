package dev.smithyai.orchestrator.model;

public record EventSource(String id, String provider) {
    public static EventSource unknown() {
        return new EventSource("", "");
    }
}
