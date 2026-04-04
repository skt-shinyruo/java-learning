package yier.bubu.base.statemachine;

import org.junit.Assert;
import org.junit.Test;

public class StateMachineTest {
    private enum SampleState {
        CREATED,
        PAID,
        CANCELLED
    }

    private enum SampleEvent {
        PAY,
        CANCEL
    }

    private static final class SampleContext {
    }

    @Test
    public void fire_shouldReturnSuccessWhenTransitionExists() {
        StateMachine<SampleState, SampleEvent, SampleContext> machine =
                new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                        .addTransition(SampleState.CREATED, SampleEvent.PAY, SampleState.PAID)
                        .build();

        SampleContext context = new SampleContext();
        TransitionResult<SampleState, SampleEvent, SampleContext> result =
                machine.fire(SampleState.CREATED, SampleEvent.PAY, context);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(SampleState.CREATED, result.getSourceState());
        Assert.assertEquals(SampleState.PAID, result.getTargetState());
        Assert.assertEquals(SampleEvent.PAY, result.getEvent());
        Assert.assertSame(context, result.getContext());
        Assert.assertNull(result.getRejectionReason());
    }

    @Test
    public void fire_shouldRejectWhenTransitionIsMissing() {
        StateMachine<SampleState, SampleEvent, SampleContext> machine =
                new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                        .addTransition(SampleState.CREATED, SampleEvent.PAY, SampleState.PAID)
                        .build();

        TransitionResult<SampleState, SampleEvent, SampleContext> result =
                machine.fire(SampleState.CREATED, SampleEvent.CANCEL, new SampleContext());

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals(SampleState.CREATED, result.getSourceState());
        Assert.assertNull(result.getTargetState());
        Assert.assertEquals(SampleEvent.CANCEL, result.getEvent());
        Assert.assertEquals(RejectionReason.NO_TRANSITION_DEFINED, result.getRejectionReason());
    }
}
