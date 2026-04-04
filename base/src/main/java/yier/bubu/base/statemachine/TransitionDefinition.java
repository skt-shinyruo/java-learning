package yier.bubu.base.statemachine;

import java.util.Objects;

final class TransitionDefinition<S extends Enum<S>, E extends Enum<E>, C> {
    private final S sourceState;
    private final E event;
    private final S targetState;
    private final TransitionGuard<S, E, C> guard;
    private final TransitionAction<S, E, C> action;

    TransitionDefinition(S sourceState,
                         E event,
                         S targetState,
                         TransitionGuard<S, E, C> guard,
                         TransitionAction<S, E, C> action) {
        this.sourceState = Objects.requireNonNull(sourceState, "sourceState");
        this.event = Objects.requireNonNull(event, "event");
        this.targetState = Objects.requireNonNull(targetState, "targetState");
        this.guard = guard;
        this.action = action;
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

    TransitionGuard<S, E, C> getGuard() {
        return guard;
    }

    TransitionAction<S, E, C> getAction() {
        return action;
    }
}
