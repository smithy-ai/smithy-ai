package dev.smithyai.orchestrator.workflow.shared;

import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StateMachine<S extends Enum<S>> {

    private static final Logger log = LoggerFactory.getLogger(StateMachine.class);

    private S state;
    private final EnumMap<S, Map<Class<? extends WorkflowEvent>, TransitionHandler<S>>> handlers;

    private StateMachine(S initial, EnumMap<S, Map<Class<? extends WorkflowEvent>, TransitionHandler<S>>> handlers) {
        this.state = initial;
        this.handlers = handlers;
    }

    public S state() {
        return state;
    }

    public boolean canFire(Class<? extends WorkflowEvent> trigger) {
        var h = handlers.get(state);
        return h != null && h.containsKey(trigger);
    }

    public void fire(WorkflowEvent event) {
        var h = handlers.get(state);
        if (h == null) {
            log.debug("No handlers registered for state {}", state);
            return;
        }
        var entry = h.get(event.getClass());
        if (entry != null) {
            S previous = this.state;
            log.debug("Firing {} in state {}", event.getClass().getSimpleName(), previous);
            this.state = entry.handle(event);
            log.debug("Transition: {} -> {}", previous, this.state);
        } else {
            log.debug("No handler for {} in state {}", event.getClass().getSimpleName(), state);
        }
    }

    public static <S extends Enum<S>> Builder<S> builder(Class<S> type, S initial) {
        return new Builder<>(type, initial);
    }

    // ── Internal ────────────────────────────────────────

    @FunctionalInterface
    interface TransitionHandler<S> {
        S handle(WorkflowEvent event);
    }

    // ── Builder ─────────────────────────────────────────

    public static class Builder<S extends Enum<S>> {

        private final Class<S> type;
        private final S initial;
        private final EnumMap<S, Map<Class<? extends WorkflowEvent>, TransitionHandler<S>>> handlers;

        private Builder(Class<S> type, S initial) {
            this.type = type;
            this.initial = initial;
            this.handlers = new EnumMap<>(type);
        }

        public StageBuilder<S> in(S stage) {
            return new StageBuilder<>(this, stage);
        }

        public StateMachine<S> build() {
            return new StateMachine<>(initial, handlers);
        }

        private void register(S stage, Class<? extends WorkflowEvent> eventType, TransitionHandler<S> handler) {
            handlers.computeIfAbsent(stage, _ -> new HashMap<>()).put(eventType, handler);
        }
    }

    public static class StageBuilder<S extends Enum<S>> {

        private final Builder<S> builder;
        private final S stage;

        private StageBuilder(Builder<S> builder, S stage) {
            this.builder = builder;
            this.stage = stage;
        }

        public <E extends WorkflowEvent> OnClause<S, E> on(Class<E> eventType, Consumer<E> handler) {
            return new OnClause<>(this, eventType, handler);
        }

        @SuppressWarnings("unchecked")
        public <E extends WorkflowEvent, R> OnClauseWithResult<S, E, R> on(Class<E> eventType, Function<E, R> handler) {
            return new OnClauseWithResult<>(this, eventType, event -> handler.apply((E) event));
        }

        public Builder<S> done() {
            return builder;
        }

        private void register(Class<? extends WorkflowEvent> eventType, TransitionHandler<S> handler) {
            builder.register(stage, eventType, handler);
        }
    }

    public static class OnClause<S extends Enum<S>, E extends WorkflowEvent> {

        private final StageBuilder<S> stageBuilder;
        private final Class<E> eventType;
        private final Consumer<WorkflowEvent> action;

        @SuppressWarnings("unchecked")
        OnClause(StageBuilder<S> stageBuilder, Class<E> eventType, Consumer<E> handler) {
            this.stageBuilder = stageBuilder;
            this.eventType = eventType;
            this.action = event -> handler.accept((E) event);
        }

        public StageBuilder<S> thenRemain() {
            S stage = stageBuilder.stage;
            stageBuilder.register(eventType, event -> {
                action.accept(event);
                return stage;
            });
            return stageBuilder;
        }

        public StageBuilder<S> then(S target) {
            stageBuilder.register(eventType, event -> {
                action.accept(event);
                return target;
            });
            return stageBuilder;
        }
    }

    public static class OnClauseWithResult<S extends Enum<S>, E extends WorkflowEvent, R> {

        private final StageBuilder<S> stageBuilder;
        private final Class<E> eventType;
        private final Function<WorkflowEvent, R> action;

        @SuppressWarnings("unchecked")
        OnClauseWithResult(StageBuilder<S> stageBuilder, Class<E> eventType, Function<E, R> handler) {
            this.stageBuilder = stageBuilder;
            this.eventType = eventType;
            this.action = event -> handler.apply((E) event);
        }

        public StageBuilder<S> thenRemain() {
            S stage = stageBuilder.stage;
            stageBuilder.register(eventType, event -> {
                action.apply(event);
                return stage;
            });
            return stageBuilder;
        }

        public StageBuilder<S> then(S target) {
            stageBuilder.register(eventType, event -> {
                action.apply(event);
                return target;
            });
            return stageBuilder;
        }

        public StageBuilder<S> then(Function<R, S> transition) {
            stageBuilder.register(eventType, event -> {
                R result = action.apply(event);
                return transition.apply(result);
            });
            return stageBuilder;
        }
    }
}
