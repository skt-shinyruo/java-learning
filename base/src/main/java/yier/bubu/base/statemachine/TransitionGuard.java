package yier.bubu.base.statemachine;

@FunctionalInterface
public interface TransitionGuard<S extends Enum<S>, E extends Enum<E>, C> {
    boolean test(TransitionContext<S, E, C> transitionContext);
}
