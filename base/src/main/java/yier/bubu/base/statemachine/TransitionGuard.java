package yier.bubu.base.statemachine;

@FunctionalInterface
public interface TransitionGuard<S extends Enum<S>, E extends Enum<E>, C> {
    /**
     * Guard logic must be side-effect free because both {@link StateMachine#fire(Enum, Enum, Object)}
     * and {@link StateMachine#canFire(Enum, Enum, Object)} may evaluate it.
     */
    boolean test(TransitionContext<S, E, C> transitionContext);
}
