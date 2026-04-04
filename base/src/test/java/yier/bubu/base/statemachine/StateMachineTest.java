package yier.bubu.base.statemachine;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
        private boolean allowPayment = true;
        private boolean paid;
        private final List<String> auditLogs = new ArrayList<String>();

        boolean isAllowPayment() {
            return allowPayment;
        }

        void setAllowPayment(boolean allowPayment) {
            this.allowPayment = allowPayment;
        }

        boolean isPaid() {
            return paid;
        }

        void markPaid() {
            this.paid = true;
        }

        List<String> getAuditLogs() {
            return auditLogs;
        }
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

        SampleContext context = new SampleContext();
        TransitionResult<SampleState, SampleEvent, SampleContext> result =
                machine.fire(SampleState.CREATED, SampleEvent.CANCEL, context);

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals(SampleState.CREATED, result.getSourceState());
        Assert.assertNull(result.getTargetState());
        Assert.assertEquals(SampleEvent.CANCEL, result.getEvent());
        Assert.assertSame(context, result.getContext());
        Assert.assertEquals(RejectionReason.NO_TRANSITION_DEFINED, result.getRejectionReason());
    }

    @Test
    public void canFire_shouldReturnTrueForExistingTransitionAndFalseForMissingTransition() {
        StateMachine<SampleState, SampleEvent, SampleContext> machine =
                new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                        .addTransition(SampleState.CREATED, SampleEvent.PAY, SampleState.PAID)
                        .build();

        SampleContext context = new SampleContext();

        Assert.assertTrue(machine.canFire(SampleState.CREATED, SampleEvent.PAY, context));
        Assert.assertFalse(machine.canFire(SampleState.CREATED, SampleEvent.CANCEL, context));
    }

    @Test
    public void fire_shouldRejectWhenGuardReturnsFalseAndSkipAction() {
        final boolean[] actionExecuted = {false};
        StateMachine<SampleState, SampleEvent, SampleContext> machine =
                new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                        .addTransition(
                                SampleState.CREATED,
                                SampleEvent.PAY,
                                SampleState.PAID,
                                transitionContext -> transitionContext.getContext().isAllowPayment(),
                                transitionContext -> actionExecuted[0] = true)
                        .build();

        SampleContext context = new SampleContext();
        context.setAllowPayment(false);

        TransitionResult<SampleState, SampleEvent, SampleContext> result =
                machine.fire(SampleState.CREATED, SampleEvent.PAY, context);

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals(RejectionReason.GUARD_REJECTED, result.getRejectionReason());
        Assert.assertFalse(actionExecuted[0]);
    }

    @Test
    public void fire_shouldRunActionAndNotifySuccessListener() {
        StateMachine<SampleState, SampleEvent, SampleContext> machine =
                new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                        .addTransition(
                                SampleState.CREATED,
                                SampleEvent.PAY,
                                SampleState.PAID,
                                null,
                                transitionContext -> {
                                    transitionContext.getContext().markPaid();
                                    transitionContext.getContext().getAuditLogs().add("action:" + transitionContext.getEvent());
                                })
                        .addListener(new StateMachineListener<SampleState, SampleEvent, SampleContext>() {
                            @Override
                            public void onSuccess(TransitionContext<SampleState, SampleEvent, SampleContext> transitionContext) {
                                transitionContext.getContext().getAuditLogs().add(
                                        "listener:" + transitionContext.getSourceState() + "->" + transitionContext.getTargetState());
                            }
                        })
                        .build();

        SampleContext context = new SampleContext();
        TransitionResult<SampleState, SampleEvent, SampleContext> result =
                machine.fire(SampleState.CREATED, SampleEvent.PAY, context);

        Assert.assertTrue(result.isSuccess());
        Assert.assertTrue(context.isPaid());
        Assert.assertEquals(Arrays.asList("action:PAY", "listener:CREATED->PAID"), context.getAuditLogs());
    }

    @Test
    public void canFire_shouldUseGuardWithoutRunningAction() {
        final boolean[] actionExecuted = {false};
        StateMachine<SampleState, SampleEvent, SampleContext> machine =
                new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                        .addTransition(
                                SampleState.CREATED,
                                SampleEvent.PAY,
                                SampleState.PAID,
                                transitionContext -> transitionContext.getContext().isAllowPayment(),
                                transitionContext -> actionExecuted[0] = true)
                        .build();

        SampleContext allowed = new SampleContext();
        allowed.setAllowPayment(true);
        Assert.assertTrue(machine.canFire(SampleState.CREATED, SampleEvent.PAY, allowed));
        Assert.assertFalse(actionExecuted[0]);

        SampleContext blocked = new SampleContext();
        blocked.setAllowPayment(false);
        Assert.assertFalse(machine.canFire(SampleState.CREATED, SampleEvent.PAY, blocked));
        Assert.assertFalse(actionExecuted[0]);
    }

    @Test
    public void fire_shouldWrapGuardFailureWithTransitionContextAndNotifyErrorListener() {
        final RuntimeException[] observed = {null};
        StateMachine<SampleState, SampleEvent, SampleContext> machine =
                new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                        .addTransition(
                                SampleState.CREATED,
                                SampleEvent.PAY,
                                SampleState.PAID,
                                transitionContext -> {
                                    throw new IllegalArgumentException("boom");
                                })
                        .addListener(new StateMachineListener<SampleState, SampleEvent, SampleContext>() {
                            @Override
                            public void onError(
                                    TransitionContext<SampleState, SampleEvent, SampleContext> transitionContext,
                                    RuntimeException exception) {
                                observed[0] = exception;
                            }
                        })
                        .build();

        try {
            machine.fire(SampleState.CREATED, SampleEvent.PAY, new SampleContext());
            Assert.fail("Expected guard failure to be rethrown");
        } catch (IllegalStateException exception) {
            Assert.assertTrue(exception.getMessage().contains("sourceState=CREATED"));
            Assert.assertTrue(exception.getMessage().contains("targetState=PAID"));
            Assert.assertTrue(exception.getMessage().contains("event=PAY"));
            Assert.assertTrue(exception.getCause() instanceof IllegalArgumentException);
            Assert.assertSame(exception, observed[0]);
        }
    }

    @Test
    public void canFire_shouldWrapGuardFailureWithTransitionContextWithoutRunningListeners() {
        final int[] listenerCalls = {0};
        StateMachine<SampleState, SampleEvent, SampleContext> machine =
                new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                        .addTransition(
                                SampleState.CREATED,
                                SampleEvent.PAY,
                                SampleState.PAID,
                                transitionContext -> {
                                    throw new IllegalArgumentException("boom");
                                })
                        .addListener(new StateMachineListener<SampleState, SampleEvent, SampleContext>() {
                            @Override
                            public void onSuccess(
                                    TransitionContext<SampleState, SampleEvent, SampleContext> transitionContext) {
                                listenerCalls[0]++;
                            }

                            @Override
                            public void onRejected(SampleState sourceState,
                                                   SampleEvent event,
                                                   SampleContext context,
                                                   RejectionReason rejectionReason) {
                                listenerCalls[0]++;
                            }

                            @Override
                            public void onError(
                                    TransitionContext<SampleState, SampleEvent, SampleContext> transitionContext,
                                    RuntimeException exception) {
                                listenerCalls[0]++;
                            }
                        })
                        .build();

        try {
            machine.canFire(SampleState.CREATED, SampleEvent.PAY, new SampleContext());
            Assert.fail("Expected guard failure to be rethrown");
        } catch (IllegalStateException exception) {
            Assert.assertTrue(exception.getMessage().contains("sourceState=CREATED"));
            Assert.assertTrue(exception.getMessage().contains("targetState=PAID"));
            Assert.assertTrue(exception.getMessage().contains("event=PAY"));
            Assert.assertTrue(exception.getCause() instanceof IllegalArgumentException);
        }

        Assert.assertEquals(0, listenerCalls[0]);
    }

    @Test
    public void addTransition_shouldRejectDuplicateDefinition() {
        StateMachineBuilder<SampleState, SampleEvent, SampleContext> builder =
                new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                        .addTransition(SampleState.CREATED, SampleEvent.PAY, SampleState.PAID);

        try {
            builder.addTransition(SampleState.CREATED, SampleEvent.PAY, SampleState.CANCELLED);
            Assert.fail("Expected duplicate transition registration to throw");
        } catch (IllegalStateException ex) {
            Assert.assertTrue(ex.getMessage().contains("sourceState=CREATED"));
            Assert.assertTrue(ex.getMessage().contains("event=PAY"));
        }
    }

    @Test
    public void build_shouldRejectEmptyDefinition() {
        StateMachineBuilder<SampleState, SampleEvent, SampleContext> builder =
                new StateMachineBuilder<SampleState, SampleEvent, SampleContext>();

        try {
            builder.build();
            Assert.fail("Expected empty builder to throw");
        } catch (IllegalStateException ex) {
            Assert.assertTrue(ex.getMessage().contains("At least one transition"));
        }
    }

    @Test
    public void fire_shouldNotifyRejectedListener() {
        final List<String> notifications = new ArrayList<String>();
        StateMachine<SampleState, SampleEvent, SampleContext> machine =
                new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                        .addTransition(SampleState.CREATED, SampleEvent.PAY, SampleState.PAID)
                        .addListener(new StateMachineListener<SampleState, SampleEvent, SampleContext>() {
                            @Override
                            public void onRejected(SampleState sourceState,
                                                   SampleEvent event,
                                                   SampleContext context,
                                                   RejectionReason rejectionReason) {
                                notifications.add(sourceState + ":" + event + ":" + rejectionReason);
                            }
                        })
                        .build();

        TransitionResult<SampleState, SampleEvent, SampleContext> result =
                machine.fire(SampleState.CREATED, SampleEvent.CANCEL, new SampleContext());

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals(Arrays.asList("CREATED:CANCEL:NO_TRANSITION_DEFINED"), notifications);
    }

    @Test
    public void fire_shouldWrapActionFailureWithTransitionContext() {
        StateMachine<SampleState, SampleEvent, SampleContext> machine =
                new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                        .addTransition(
                                SampleState.CREATED,
                                SampleEvent.PAY,
                                SampleState.PAID,
                                null,
                                transitionContext -> {
                                    throw new IllegalArgumentException("boom");
                                })
                        .build();

        try {
            machine.fire(SampleState.CREATED, SampleEvent.PAY, new SampleContext());
            Assert.fail("Expected action failure to be rethrown");
        } catch (IllegalStateException exception) {
            Assert.assertTrue(exception.getMessage().contains("sourceState=CREATED"));
            Assert.assertTrue(exception.getMessage().contains("targetState=PAID"));
            Assert.assertTrue(exception.getMessage().contains("event=PAY"));
            Assert.assertTrue(exception.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void fire_shouldNotifyErrorListenerBeforeRethrowingActionFailure() {
        final List<String> notifications = new ArrayList<String>();
        StateMachine<SampleState, SampleEvent, SampleContext> machine =
                new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                        .addTransition(
                                SampleState.CREATED,
                                SampleEvent.PAY,
                                SampleState.PAID,
                                null,
                                transitionContext -> {
                                    throw new IllegalArgumentException("boom");
                                })
                        .addListener(new StateMachineListener<SampleState, SampleEvent, SampleContext>() {
                            @Override
                            public void onError(
                                    TransitionContext<SampleState, SampleEvent, SampleContext> transitionContext,
                                    RuntimeException exception) {
                                notifications.add(
                                        transitionContext.getSourceState() + "->" + transitionContext.getTargetState());
                            }
                        })
                        .build();

        try {
            machine.fire(SampleState.CREATED, SampleEvent.PAY, new SampleContext());
            Assert.fail("Expected action failure to be rethrown");
        } catch (IllegalStateException exception) {
            Assert.assertEquals(Arrays.asList("CREATED->PAID"), notifications);
        }
    }

    @Test
    public void fire_shouldPreserveActionFailureWhenErrorListenerThrows() {
        StateMachine<SampleState, SampleEvent, SampleContext> machine =
                new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                        .addTransition(
                                SampleState.CREATED,
                                SampleEvent.PAY,
                                SampleState.PAID,
                                null,
                                transitionContext -> {
                                    throw new IllegalArgumentException("boom");
                                })
                        .addListener(new StateMachineListener<SampleState, SampleEvent, SampleContext>() {
                            @Override
                            public void onError(
                                    TransitionContext<SampleState, SampleEvent, SampleContext> transitionContext,
                                    RuntimeException exception) {
                                throw new IllegalStateException("listener failure");
                            }
                        })
                        .build();

        try {
            machine.fire(SampleState.CREATED, SampleEvent.PAY, new SampleContext());
            Assert.fail("Expected action failure to be rethrown");
        } catch (IllegalStateException exception) {
            Assert.assertTrue(exception.getMessage().contains("Failed to execute transition action"));
            Assert.assertEquals(1, exception.getSuppressed().length);
            Assert.assertTrue(exception.getSuppressed()[0].getMessage().contains("sourceState=CREATED"));
            Assert.assertTrue(exception.getSuppressed()[0].getMessage().contains("targetState=PAID"));
            Assert.assertTrue(exception.getSuppressed()[0].getMessage().contains("event=PAY"));
            Assert.assertTrue(exception.getSuppressed()[0].getCause() instanceof IllegalStateException);
            Assert.assertEquals("listener failure", exception.getSuppressed()[0].getCause().getMessage());
        }
    }

    @Test
    public void fire_shouldReturnSuccessWhenSuccessListenerFailsAndNotifyErrorListener() {
        final RuntimeException[] observed = {null};
        StateMachine<SampleState, SampleEvent, SampleContext> machine =
                new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                        .addTransition(
                                SampleState.CREATED,
                                SampleEvent.PAY,
                                SampleState.PAID,
                                null,
                                transitionContext -> transitionContext.getContext().markPaid())
                        .addListener(new StateMachineListener<SampleState, SampleEvent, SampleContext>() {
                            @Override
                            public void onSuccess(
                                    TransitionContext<SampleState, SampleEvent, SampleContext> transitionContext) {
                                throw new IllegalStateException("listener failure");
                            }
                        })
                        .addListener(new StateMachineListener<SampleState, SampleEvent, SampleContext>() {
                            @Override
                            public void onError(
                                    TransitionContext<SampleState, SampleEvent, SampleContext> transitionContext,
                                    RuntimeException exception) {
                                observed[0] = exception;
                            }
                        })
                        .build();

        SampleContext context = new SampleContext();
        TransitionResult<SampleState, SampleEvent, SampleContext> result =
                machine.fire(SampleState.CREATED, SampleEvent.PAY, context);

        Assert.assertTrue(result.isSuccess());
        Assert.assertTrue(context.isPaid());
        Assert.assertNotNull(observed[0]);
        Assert.assertTrue(observed[0].getMessage().contains("sourceState=CREATED"));
        Assert.assertTrue(observed[0].getMessage().contains("targetState=PAID"));
        Assert.assertTrue(observed[0].getMessage().contains("event=PAY"));
        Assert.assertTrue(observed[0].getCause() instanceof IllegalStateException);
        Assert.assertEquals("listener failure", observed[0].getCause().getMessage());
    }

    @Test
    public void fire_shouldWrapRejectedListenerFailureWithTransitionContext() {
        StateMachine<SampleState, SampleEvent, SampleContext> machine =
                new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                        .addTransition(SampleState.CREATED, SampleEvent.PAY, SampleState.PAID)
                        .addListener(new StateMachineListener<SampleState, SampleEvent, SampleContext>() {
                            @Override
                            public void onRejected(SampleState sourceState,
                                                   SampleEvent event,
                                                   SampleContext context,
                                                   RejectionReason rejectionReason) {
                                throw new IllegalStateException("listener failure");
                            }
                        })
                        .build();

        try {
            machine.fire(SampleState.CREATED, SampleEvent.CANCEL, new SampleContext());
            Assert.fail("Expected listener failure to propagate");
        } catch (IllegalStateException exception) {
            Assert.assertTrue(exception.getMessage().contains("sourceState=CREATED"));
            Assert.assertTrue(exception.getMessage().contains("targetState=null"));
            Assert.assertTrue(exception.getMessage().contains("event=CANCEL"));
            Assert.assertTrue(exception.getCause() instanceof IllegalStateException);
            Assert.assertEquals("listener failure", exception.getCause().getMessage());
        }
    }

    @Test
    public void fire_shouldWrapGuardRejectedListenerFailureWithKnownTargetState() {
        StateMachine<SampleState, SampleEvent, SampleContext> machine =
                new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                        .addTransition(
                                SampleState.CREATED,
                                SampleEvent.PAY,
                                SampleState.PAID,
                                transitionContext -> false)
                        .addListener(new StateMachineListener<SampleState, SampleEvent, SampleContext>() {
                            @Override
                            public void onRejected(SampleState sourceState,
                                                   SampleEvent event,
                                                   SampleContext context,
                                                   RejectionReason rejectionReason) {
                                throw new IllegalStateException("listener failure");
                            }
                        })
                        .build();

        try {
            machine.fire(SampleState.CREATED, SampleEvent.PAY, new SampleContext());
            Assert.fail("Expected listener failure to propagate");
        } catch (IllegalStateException exception) {
            Assert.assertTrue(exception.getMessage().contains("sourceState=CREATED"));
            Assert.assertTrue(exception.getMessage().contains("targetState=PAID"));
            Assert.assertTrue(exception.getMessage().contains("event=PAY"));
            Assert.assertTrue(exception.getCause() instanceof IllegalStateException);
            Assert.assertEquals("listener failure", exception.getCause().getMessage());
        }
    }

    @Test
    public void build_shouldSnapshotTransitions() {
        StateMachineBuilder<SampleState, SampleEvent, SampleContext> builder =
                new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                        .addTransition(SampleState.CREATED, SampleEvent.PAY, SampleState.PAID);
        StateMachine<SampleState, SampleEvent, SampleContext> machine = builder.build();

        builder.addTransition(SampleState.CREATED, SampleEvent.CANCEL, SampleState.CANCELLED);
        StateMachine<SampleState, SampleEvent, SampleContext> updatedMachine = builder.build();
        SampleContext context = new SampleContext();

        Assert.assertFalse(machine.canFire(SampleState.CREATED, SampleEvent.CANCEL, context));
        Assert.assertTrue(updatedMachine.canFire(SampleState.CREATED, SampleEvent.CANCEL, context));
    }

    @Test
    public void build_shouldSnapshotListeners() {
        StateMachineBuilder<SampleState, SampleEvent, SampleContext> builder =
                new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                        .addTransition(SampleState.CREATED, SampleEvent.PAY, SampleState.PAID);
        builder.addListener(new StateMachineListener<SampleState, SampleEvent, SampleContext>() {
            @Override
            public void onSuccess(TransitionContext<SampleState, SampleEvent, SampleContext> transitionContext) {
                transitionContext.getContext().getAuditLogs().add("initial");
            }
        });
        StateMachine<SampleState, SampleEvent, SampleContext> machine = builder.build();

        builder.addListener(new StateMachineListener<SampleState, SampleEvent, SampleContext>() {
            @Override
            public void onSuccess(TransitionContext<SampleState, SampleEvent, SampleContext> transitionContext) {
                transitionContext.getContext().getAuditLogs().add("late");
            }
        });
        StateMachine<SampleState, SampleEvent, SampleContext> updatedMachine = builder.build();

        SampleContext firstContext = new SampleContext();
        machine.fire(SampleState.CREATED, SampleEvent.PAY, firstContext);
        Assert.assertEquals(Arrays.asList("initial"), firstContext.getAuditLogs());

        SampleContext secondContext = new SampleContext();
        updatedMachine.fire(SampleState.CREATED, SampleEvent.PAY, secondContext);
        Assert.assertEquals(Arrays.asList("initial", "late"), secondContext.getAuditLogs());
    }
}
