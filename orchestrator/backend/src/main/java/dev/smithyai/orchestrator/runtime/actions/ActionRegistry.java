package dev.smithyai.orchestrator.runtime.actions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Every action the engine can run, discovered as Spring beans and indexed by
 * {@link WorkflowAction#type()}.
 */
@Slf4j
@Component
public class ActionRegistry {

    private final Map<String, WorkflowAction> byType = new LinkedHashMap<>();

    public ActionRegistry(List<WorkflowAction> actions) {
        for (var action : actions) {
            var clash = byType.put(action.type(), action);
            if (clash != null) {
                throw new IllegalStateException(
                    "Two actions claim the type '%s': %s and %s".formatted(
                        action.type(),
                        clash.getClass().getName(),
                        action.getClass().getName()
                    )
                );
            }
        }
        log.info("Action registry initialized with {} actions: {}", byType.size(), byType.keySet());
    }

    public Optional<WorkflowAction> find(String type) {
        return Optional.ofNullable(byType.get(type));
    }

    public Set<String> types() {
        return byType.keySet();
    }

    /** Everything the given action types need a provider to support. */
    public Set<Capability> capabilitiesFor(Iterable<String> actionTypes) {
        var required = new java.util.LinkedHashSet<Capability>();
        for (var type : actionTypes) {
            find(type).ifPresent(action -> required.addAll(action.requires()));
        }
        return required;
    }
}
