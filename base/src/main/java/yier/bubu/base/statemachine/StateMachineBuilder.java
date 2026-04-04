package yier.bubu.base.statemachine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class StateMachineBuilder<S extends Enum<S>, E extends Enum<E>, C> {
    private final Map<S, Map<E, TransitionDefinition<S, E, C>>> transitions =
            new LinkedHashMap<S, Map<E, TransitionDefinition<S, E, C>>>();
    private final List<StateMachineListener<S, E, C>> listeners =
            new ArrayList<StateMachineListener<S, E, C>>();

    public StateMachineBuilder<S, E, C> addTransition(S from, E event, S to) {
        return addTransition(from, event, to, null, null);
    }

    public StateMachineBuilder<S, E, C> addTransition(
            S from,
            E event,
            S to,
            TransitionGuard<S, E, C> guard) {
        return addTransition(from, event, to, guard, null);
    }

    public StateMachineBuilder<S, E, C> addTransition(
            S from,
            E event,
            S to,
            TransitionGuard<S, E, C> guard,
            TransitionAction<S, E, C> action) {
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

        byEvent.put(event, new TransitionDefinition<S, E, C>(from, event, to, guard, action));
        return this;
    }

    public StateMachineBuilder<S, E, C> addListener(StateMachineListener<S, E, C> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    public StateMachine<S, E, C> build() {
        if (transitions.isEmpty()) {
            throw new IllegalStateException("At least one transition must be defined");
        }

        Map<S, Map<E, TransitionDefinition<S, E, C>>> transitionSnapshot =
                new LinkedHashMap<S, Map<E, TransitionDefinition<S, E, C>>>();
        for (Map.Entry<S, Map<E, TransitionDefinition<S, E, C>>> entry : transitions.entrySet()) {
            transitionSnapshot.put(entry.getKey(),
                    Collections.unmodifiableMap(new LinkedHashMap<E, TransitionDefinition<S, E, C>>(entry.getValue())));
        }

        List<StateMachineListener<S, E, C>> listenerSnapshot =
                Collections.unmodifiableList(new ArrayList<StateMachineListener<S, E, C>>(listeners));

        return new StateMachine<S, E, C>(
                Collections.unmodifiableMap(transitionSnapshot),
                listenerSnapshot);
    }
}
