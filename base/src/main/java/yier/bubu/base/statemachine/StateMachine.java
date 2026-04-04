package yier.bubu.base.statemachine;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class StateMachine<S extends Enum<S>, E extends Enum<E>, C> {
    private final Map<S, Map<E, TransitionDefinition<S, E, C>>> transitions;
    private final List<StateMachineListener<S, E, C>> listeners;

    StateMachine(Map<S, Map<E, TransitionDefinition<S, E, C>>> transitions,
                 List<StateMachineListener<S, E, C>> listeners) {
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        this.listeners = Objects.requireNonNull(listeners, "listeners");
    }

    // 状态机本身不保存当前状态，便于多个业务对象复用同一份状态定义。
    public TransitionResult<S, E, C> fire(S sourceState, E event, C context) {
        validateInputs(sourceState, event);

        TransitionDefinition<S, E, C> definition = findDefinition(sourceState, event);
        if (definition == null) {
            notifyRejected(sourceState, event, context, RejectionReason.NO_TRANSITION_DEFINED);
            return TransitionResult.rejected(
                    sourceState,
                    null,
                    event,
                    context,
                    RejectionReason.NO_TRANSITION_DEFINED);
        }

        TransitionContext<S, E, C> transitionContext =
                new TransitionContext<S, E, C>(sourceState, definition.getTargetState(), event, context);

        if (definition.getGuard() != null && !definition.getGuard().test(transitionContext)) {
            // 守卫失败代表业务条件不满足，返回拒绝结果即可，不把它当作系统异常。
            notifyRejected(sourceState, event, context, RejectionReason.GUARD_REJECTED);
            return TransitionResult.rejected(
                    sourceState,
                    null,
                    event,
                    context,
                    RejectionReason.GUARD_REJECTED);
        }

        try {
            if (definition.getAction() != null) {
                definition.getAction().execute(transitionContext);
            }
        } catch (RuntimeException exception) {
            // 动作抛异常说明迁移执行过程出错，需要把异常抛给上层决定是否回滚或告警。
            RuntimeException wrapped = new IllegalStateException(
                    "Failed to execute transition action: sourceState=" + sourceState
                            + ", targetState=" + definition.getTargetState()
                            + ", event=" + event,
                    exception);
            notifyError(transitionContext, wrapped);
            throw wrapped;
        }

        notifySuccess(transitionContext);
        return TransitionResult.success(sourceState, definition.getTargetState(), event, context);
    }

    public boolean canFire(S sourceState, E event, C context) {
        validateInputs(sourceState, event);

        TransitionDefinition<S, E, C> definition = findDefinition(sourceState, event);
        if (definition == null) {
            return false;
        }
        if (definition.getGuard() == null) {
            return true;
        }

        TransitionContext<S, E, C> transitionContext =
                new TransitionContext<S, E, C>(sourceState, definition.getTargetState(), event, context);
        return definition.getGuard().test(transitionContext);
    }

    private void validateInputs(S sourceState, E event) {
        Objects.requireNonNull(sourceState, "sourceState");
        Objects.requireNonNull(event, "event");
    }

    private TransitionDefinition<S, E, C> findDefinition(S sourceState, E event) {
        Map<E, TransitionDefinition<S, E, C>> byEvent = transitions.get(sourceState);
        if (byEvent == null) {
            return null;
        }
        return byEvent.get(event);
    }

    private void notifySuccess(TransitionContext<S, E, C> transitionContext) {
        for (StateMachineListener<S, E, C> listener : listeners) {
            listener.onSuccess(transitionContext);
        }
    }

    private void notifyRejected(S sourceState, E event, C context, RejectionReason rejectionReason) {
        for (StateMachineListener<S, E, C> listener : listeners) {
            listener.onRejected(sourceState, event, context, rejectionReason);
        }
    }

    private void notifyError(TransitionContext<S, E, C> transitionContext, RuntimeException exception) {
        for (StateMachineListener<S, E, C> listener : listeners) {
            listener.onError(transitionContext, exception);
        }
    }
}
