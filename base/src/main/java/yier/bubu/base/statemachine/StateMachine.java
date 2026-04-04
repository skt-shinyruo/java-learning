package yier.bubu.base.statemachine;

import java.util.Map;
import java.util.Objects;

public final class StateMachine<S extends Enum<S>, E extends Enum<E>, C> {
    private final Map<S, Map<E, TransitionDefinition<S, E, C>>> transitions;

    StateMachine(Map<S, Map<E, TransitionDefinition<S, E, C>>> transitions) {
        this.transitions = Objects.requireNonNull(transitions, "transitions");
    }

    // 状态机本身不保存当前状态，便于多个业务对象复用同一份状态定义。
    public TransitionResult<S, E, C> fire(S sourceState, E event, C context) {
        Objects.requireNonNull(sourceState, "sourceState");
        Objects.requireNonNull(event, "event");

        TransitionDefinition<S, E, C> definition = findDefinition(sourceState, event);
        if (definition == null) {
            return TransitionResult.rejected(
                    sourceState,
                    null,
                    event,
                    context,
                    RejectionReason.NO_TRANSITION_DEFINED);
        }
        return TransitionResult.success(sourceState, definition.getTargetState(), event, context);
    }

    public boolean canFire(S sourceState, E event, C context) {
        Objects.requireNonNull(sourceState, "sourceState");
        Objects.requireNonNull(event, "event");
        return findDefinition(sourceState, event) != null;
    }

    private TransitionDefinition<S, E, C> findDefinition(S sourceState, E event) {
        Map<E, TransitionDefinition<S, E, C>> byEvent = transitions.get(sourceState);
        if (byEvent == null) {
            return null;
        }
        return byEvent.get(event);
    }
}
