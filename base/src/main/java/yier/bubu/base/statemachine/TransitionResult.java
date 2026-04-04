package yier.bubu.base.statemachine;

public final class TransitionResult<S extends Enum<S>, E extends Enum<E>, C> {
    private final boolean success;
    private final S sourceState;
    private final S targetState;
    private final E event;
    private final C context;
    private final RejectionReason rejectionReason;

    private TransitionResult(boolean success,
                             S sourceState,
                             S targetState,
                             E event,
                             C context,
                             RejectionReason rejectionReason) {
        this.success = success;
        this.sourceState = sourceState;
        this.targetState = targetState;
        this.event = event;
        this.context = context;
        this.rejectionReason = rejectionReason;
    }

    public static <S extends Enum<S>, E extends Enum<E>, C> TransitionResult<S, E, C> success(
            S sourceState,
            S targetState,
            E event,
            C context) {
        return new TransitionResult<S, E, C>(true, sourceState, targetState, event, context, null);
    }

    public static <S extends Enum<S>, E extends Enum<E>, C> TransitionResult<S, E, C> rejected(
            S sourceState,
            S targetState,
            E event,
            C context,
            RejectionReason rejectionReason) {
        return new TransitionResult<S, E, C>(false, sourceState, targetState, event, context, rejectionReason);
    }

    public boolean isSuccess() {
        return success;
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

    public RejectionReason getRejectionReason() {
        return rejectionReason;
    }
}
