package yier.bubu.base.statemachine;

public interface StateMachineListener<S extends Enum<S>, E extends Enum<E>, C> {
    default void onSuccess(TransitionContext<S, E, C> transitionContext) {
    }

    default void onRejected(S sourceState, E event, C context, RejectionReason rejectionReason) {
    }

    default void onError(TransitionContext<S, E, C> transitionContext, RuntimeException exception) {
    }
}
