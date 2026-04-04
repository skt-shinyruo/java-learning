package yier.bubu.base.statemachine;

@FunctionalInterface
public interface TransitionAction<S extends Enum<S>, E extends Enum<E>, C> {
    void execute(TransitionContext<S, E, C> transitionContext);
}
