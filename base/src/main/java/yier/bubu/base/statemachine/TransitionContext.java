package yier.bubu.base.statemachine;

import java.util.Objects;

public final class TransitionContext<S extends Enum<S>, E extends Enum<E>, C> {
    private final S sourceState;
    private final S targetState;
    private final E event;
    private final C context;

    public TransitionContext(S sourceState, S targetState, E event, C context) {
        this.sourceState = Objects.requireNonNull(sourceState, "sourceState");
        this.targetState = Objects.requireNonNull(targetState, "targetState");
        this.event = Objects.requireNonNull(event, "event");
        this.context = context;
    }

    public S getSourceState() {
        return sourceState;
    }

    public S getTargetState() {
        return targetState;
    }

    public E getEvent() {
        return event;
    }

    public C getContext() {
        return context;
    }
}
