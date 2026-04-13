package dev.smithyai.orchestrator.workflow;

public sealed interface EventAction {
    record Create(String key) implements EventAction {}

    record Dispatch(String key) implements EventAction {}

    record Destroy(String key) implements EventAction {}

    record Ignore() implements EventAction {}

    EventAction IGNORE = new Ignore();
}
