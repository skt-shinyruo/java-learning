package yier.bubu.base.statemachine;

import java.util.Objects;

public final class StateMachineInstance<S extends Enum<S>, E extends Enum<E>, C> {
    private final StateMachine<S, E, C> stateMachine;
    private final C context;
    private S currentState;

    public StateMachineInstance(StateMachine<S, E, C> stateMachine, S initialState, C context) {
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.currentState = Objects.requireNonNull(initialState, "initialState");
        this.context = context;
    }

    public S getCurrentState() {
        return currentState;
    }

    public C getContext() {
        return context;
    }

    public boolean canFire(E event) {
        return stateMachine.canFire(currentState, event, context);
    }

    // 实例对象持有当前状态和上下文；底层 StateMachine 仍然只负责状态流转规则。
    public TransitionResult<S, E, C> fire(E event) {
        TransitionResult<S, E, C> result = stateMachine.fire(currentState, event, context);
        if (result.isSuccess()) {
            currentState = result.getTargetState();
        }
        return result;
    }
}
