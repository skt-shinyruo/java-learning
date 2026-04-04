package yier.bubu.base.statemachine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class StateMachineBuilder<S extends Enum<S>, E extends Enum<E>, C> {
    private final Map<S, Map<E, TransitionDefinition<S, E, C>>> transitions =
            new LinkedHashMap<S, Map<E, TransitionDefinition<S, E, C>>>();

    public StateMachineBuilder<S, E, C> addTransition(S from, E event, S to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(to, "to");

        Map<E, TransitionDefinition<S, E, C>> byEvent = transitions.get(from);
        if (byEvent == null) {
            byEvent = new LinkedHashMap<E, TransitionDefinition<S, E, C>>();
            transitions.put(from, byEvent);
        }
        if (byEvent.containsKey(event)) {
            throw new IllegalStateException(
                    "Duplicate transition definition for sourceState=" + from + ", event=" + event);
        }

        byEvent.put(event, new TransitionDefinition<S, E, C>(from, event, to));
        return this;
    }

    public StateMachine<S, E, C> build() {
        if (transitions.isEmpty()) {
            throw new IllegalStateException("At least one transition must be defined");
        }

        Map<S, Map<E, TransitionDefinition<S, E, C>>> snapshot =
                new LinkedHashMap<S, Map<E, TransitionDefinition<S, E, C>>>();
        for (Map.Entry<S, Map<E, TransitionDefinition<S, E, C>>> entry : transitions.entrySet()) {
            snapshot.put(entry.getKey(),
                    Collections.unmodifiableMap(new LinkedHashMap<E, TransitionDefinition<S, E, C>>(entry.getValue())));
        }
        return new StateMachine<S, E, C>(Collections.unmodifiableMap(snapshot));
    }
}
