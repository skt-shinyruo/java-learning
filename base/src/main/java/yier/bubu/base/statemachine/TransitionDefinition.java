package yier.bubu.base.statemachine;

import java.util.Objects;

final class TransitionDefinition<S extends Enum<S>, E extends Enum<E>, C> {
    private final S sourceState;
    private final E event;
    private final S targetState;

    TransitionDefinition(S sourceState, E event, S targetState) {
        this.sourceState = Objects.requireNonNull(sourceState, "sourceState");
        this.event = Objects.requireNonNull(event, "event");
        this.targetState = Objects.requireNonNull(targetState, "targetState");
    }

    S getSourceState() {
        return sourceState;
    }

    E getEvent() {
        return event;
    }

    S getTargetState() {
        return targetState;
    }
}
