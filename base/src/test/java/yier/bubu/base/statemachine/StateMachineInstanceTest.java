package yier.bubu.base.statemachine;

import org.junit.Assert;
import org.junit.Test;

public class StateMachineInstanceTest {
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
        private boolean paid;

        boolean isPaid() {
            return paid;
        }

        void markPaid() {
            this.paid = true;
        }
    }

    @Test
    public void constructor_shouldRejectNullStateMachine() {
        try {
            new StateMachineInstance<SampleState, SampleEvent, SampleContext>(
                    null,
                    SampleState.CREATED,
                    new SampleContext());
            Assert.fail("Expected null stateMachine to be rejected");
        } catch (NullPointerException exception) {
            Assert.assertEquals("stateMachine", exception.getMessage());
        }
    }

    @Test
    public void constructor_shouldRejectNullInitialState() {
        try {
            new StateMachineInstance<SampleState, SampleEvent, SampleContext>(
                    new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                            .addTransition(SampleState.CREATED, SampleEvent.PAY, SampleState.PAID)
                            .build(),
                    null,
                    new SampleContext());
            Assert.fail("Expected null initialState to be rejected");
        } catch (NullPointerException exception) {
            Assert.assertEquals("initialState", exception.getMessage());
        }
    }

    @Test
    public void constructor_shouldExposeInitialStateAndContext() {
        SampleContext context = new SampleContext();
        StateMachineInstance<SampleState, SampleEvent, SampleContext> instance =
                new StateMachineInstance<SampleState, SampleEvent, SampleContext>(
                        new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                                .addTransition(SampleState.CREATED, SampleEvent.PAY, SampleState.PAID)
                                .build(),
                        SampleState.CREATED,
                        context);

        Assert.assertEquals(SampleState.CREATED, instance.getCurrentState());
        Assert.assertSame(context, instance.getContext());
    }

    @Test
    public void canFire_shouldDelegateUsingInternalState() {
        StateMachineInstance<SampleState, SampleEvent, SampleContext> instance =
                new StateMachineInstance<SampleState, SampleEvent, SampleContext>(
                        new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                                .addTransition(SampleState.CREATED, SampleEvent.PAY, SampleState.PAID)
                                .build(),
                        SampleState.CREATED,
                        new SampleContext());

        Assert.assertTrue(instance.canFire(SampleEvent.PAY));
        Assert.assertFalse(instance.canFire(SampleEvent.CANCEL));
    }

    @Test
    public void fire_shouldAdvanceCurrentStateOnSuccess() {
        SampleContext context = new SampleContext();
        StateMachineInstance<SampleState, SampleEvent, SampleContext> instance =
                new StateMachineInstance<SampleState, SampleEvent, SampleContext>(
                        new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                                .addTransition(
                                        SampleState.CREATED,
                                        SampleEvent.PAY,
                                        SampleState.PAID,
                                        null,
                                        transitionContext -> transitionContext.getContext().markPaid())
                                .build(),
                        SampleState.CREATED,
                        context);

        TransitionResult<SampleState, SampleEvent, SampleContext> result = instance.fire(SampleEvent.PAY);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(SampleState.PAID, result.getTargetState());
        Assert.assertEquals(SampleState.PAID, instance.getCurrentState());
        Assert.assertTrue(context.isPaid());
    }

    @Test
    public void fire_shouldKeepCurrentStateWhenTransitionIsRejected() {
        StateMachineInstance<SampleState, SampleEvent, SampleContext> instance =
                new StateMachineInstance<SampleState, SampleEvent, SampleContext>(
                        new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                                .addTransition(SampleState.CREATED, SampleEvent.PAY, SampleState.PAID)
                                .build(),
                        SampleState.CREATED,
                        new SampleContext());

        TransitionResult<SampleState, SampleEvent, SampleContext> result = instance.fire(SampleEvent.CANCEL);

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals(RejectionReason.NO_TRANSITION_DEFINED, result.getRejectionReason());
        Assert.assertEquals(SampleState.CREATED, instance.getCurrentState());
    }

    @Test
    public void fire_shouldKeepCurrentStateWhenTransitionThrows() {
        StateMachineInstance<SampleState, SampleEvent, SampleContext> instance =
                new StateMachineInstance<SampleState, SampleEvent, SampleContext>(
                        new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                                .addTransition(
                                        SampleState.CREATED,
                                        SampleEvent.PAY,
                                        SampleState.PAID,
                                        null,
                                        transitionContext -> {
                                            throw new IllegalArgumentException("boom");
                                        })
                                .build(),
                        SampleState.CREATED,
                        new SampleContext());

        try {
            instance.fire(SampleEvent.PAY);
            Assert.fail("Expected transition failure to be rethrown");
        } catch (IllegalStateException exception) {
            Assert.assertTrue(exception.getMessage().contains("sourceState=CREATED"));
            Assert.assertTrue(exception.getMessage().contains("targetState=PAID"));
            Assert.assertTrue(exception.getMessage().contains("event=PAY"));
        }

        Assert.assertEquals(SampleState.CREATED, instance.getCurrentState());
    }
}
