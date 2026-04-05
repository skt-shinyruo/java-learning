# State Machine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a reusable, in-process, strongly typed Java state machine framework in `base`, then implement an order lifecycle example on top of it with executable JUnit 4 tests and necessary Chinese comments.

**Architecture:** The framework lives under `yier.bubu.base.statemachine` and is table-driven: `StateMachineBuilder` collects immutable `TransitionDefinition` rules and produces a reusable `StateMachine` that evaluates transitions synchronously against caller-supplied state, event, and context. The order example lives under `yier.bubu.base.statemachine.order`, defines `enum` states/events plus an `OrderContext`, and assembles a default order machine that demonstrates happy path, failure path, guard rejection, action mutation, and listener-based audit logging.

**Tech Stack:** Java 8, Maven, JUnit 4, JDK collections/utilities only

---

## File Structure

- Create: `base/src/main/java/yier/bubu/base/statemachine/RejectionReason.java`
  Responsibility: define the explicit rejection reasons returned for business-level transition rejection.
- Create: `base/src/main/java/yier/bubu/base/statemachine/TransitionResult.java`
  Responsibility: carry success/rejection outcome data for `fire(...)`.
- Create: `base/src/main/java/yier/bubu/base/statemachine/TransitionDefinition.java`
  Responsibility: immutable internal transition rule record used by the built state machine.
- Create: `base/src/main/java/yier/bubu/base/statemachine/TransitionContext.java`
  Responsibility: runtime object passed to guards, actions, and listeners.
- Create: `base/src/main/java/yier/bubu/base/statemachine/TransitionGuard.java`
  Responsibility: functional interface for synchronous transition admission checks.
- Create: `base/src/main/java/yier/bubu/base/statemachine/TransitionAction.java`
  Responsibility: functional interface for synchronous transition side effects.
- Create: `base/src/main/java/yier/bubu/base/statemachine/StateMachineListener.java`
  Responsibility: listener contract for success, rejection, and error callbacks.
- Create: `base/src/main/java/yier/bubu/base/statemachine/StateMachineBuilder.java`
  Responsibility: collect transition rules and listeners, validate definitions, build immutable machines.
- Create: `base/src/main/java/yier/bubu/base/statemachine/StateMachine.java`
  Responsibility: perform rule lookup, guard evaluation, action execution, listener notification, and result creation.
- Create: `base/src/main/java/yier/bubu/base/statemachine/package-info.java`
  Responsibility: package-level documentation for the reusable framework and its execution boundaries.
- Create: `base/src/main/java/yier/bubu/base/statemachine/order/OrderState.java`
  Responsibility: enumerate supported business states for the order example.
- Create: `base/src/main/java/yier/bubu/base/statemachine/order/OrderEvent.java`
  Responsibility: enumerate supported business events for the order example.
- Create: `base/src/main/java/yier/bubu/base/statemachine/order/OrderContext.java`
  Responsibility: hold order business facts and mutable audit fields that guards/actions/listeners use.
- Create: `base/src/main/java/yier/bubu/base/statemachine/order/OrderStateMachineFactory.java`
  Responsibility: assemble the default order workflow using the generic state machine builder.
- Create: `base/src/test/java/yier/bubu/base/statemachine/StateMachineTest.java`
  Responsibility: executable documentation for the reusable framework.
- Create: `base/src/test/java/yier/bubu/base/statemachine/order/OrderStateMachineTest.java`
  Responsibility: executable documentation for the order workflow example.

## Preflight

- Work in the current workspace and do not revert unrelated changes.
- Use TDD for each task slice: write the failing test first, run it to verify the red state, then write the minimal implementation.
- Use focused Maven test runs against the `base` module until the final regression step.
- Add Chinese comments only where they clarify design intent or tricky runtime semantics; do not comment obvious code.

### Task 1: Add the Core Transition Result and Rule Lookup Slice

**Files:**
- Create: `base/src/test/java/yier/bubu/base/statemachine/StateMachineTest.java`
- Create: `base/src/main/java/yier/bubu/base/statemachine/RejectionReason.java`
- Create: `base/src/main/java/yier/bubu/base/statemachine/TransitionResult.java`
- Create: `base/src/main/java/yier/bubu/base/statemachine/TransitionDefinition.java`
- Create: `base/src/main/java/yier/bubu/base/statemachine/StateMachineBuilder.java`
- Create: `base/src/main/java/yier/bubu/base/statemachine/StateMachine.java`

- [ ] **Step 1: Write the first failing framework tests for success and missing-rule rejection**

```java
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
```

- [ ] **Step 2: Run the focused framework test class to verify it is red**

Run: `mvn -pl base -am test -Dtest=StateMachineTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because `StateMachine`, `StateMachineBuilder`, `TransitionResult`, and `RejectionReason` do not exist yet.

- [ ] **Step 3: Write the minimal framework implementation that makes the initial tests pass**

`base/src/main/java/yier/bubu/base/statemachine/RejectionReason.java`

```java
package yier.bubu.base.statemachine;

public enum RejectionReason {
    NO_TRANSITION_DEFINED,
    GUARD_REJECTED
}
```

`base/src/main/java/yier/bubu/base/statemachine/TransitionResult.java`

```java
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
```

`base/src/main/java/yier/bubu/base/statemachine/TransitionDefinition.java`

```java
package yier.bubu.base.statemachine;

import java.util.Objects;

final class TransitionDefinition<S extends Enum<S>, E extends Enum<E>, C> {
    private final S sourceState;
    private final E event;
    private final S targetState;

    TransitionDefinition(S sourceState, E event, S targetState) {
        this.sourceState = Objects.requireNonNull(sourceState, "sourceState");
        this.event = Objects.requireNonNull(event, "event");
        this.targetState = Objects.requireNonNull(targetState, "targetState");
    }

    S getSourceState() {
        return sourceState;
    }

    E getEvent() {
        return event;
    }

    S getTargetState() {
        return targetState;
    }
}
```

`base/src/main/java/yier/bubu/base/statemachine/StateMachineBuilder.java`

```java
package yier.bubu.base.statemachine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class StateMachineBuilder<S extends Enum<S>, E extends Enum<E>, C> {
    private final Map<S, Map<E, TransitionDefinition<S, E, C>>> transitions =
            new LinkedHashMap<S, Map<E, TransitionDefinition<S, E, C>>>();

    public StateMachineBuilder<S, E, C> addTransition(S from, E event, S to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(to, "to");

        Map<E, TransitionDefinition<S, E, C>> byEvent = transitions.get(from);
        if (byEvent == null) {
            byEvent = new LinkedHashMap<E, TransitionDefinition<S, E, C>>();
            transitions.put(from, byEvent);
        }
        if (byEvent.containsKey(event)) {
            throw new IllegalStateException(
                    "Duplicate transition definition for sourceState=" + from + ", event=" + event);
        }

        byEvent.put(event, new TransitionDefinition<S, E, C>(from, event, to));
        return this;
    }

    public StateMachine<S, E, C> build() {
        if (transitions.isEmpty()) {
            throw new IllegalStateException("At least one transition must be defined");
        }

        Map<S, Map<E, TransitionDefinition<S, E, C>>> snapshot =
                new LinkedHashMap<S, Map<E, TransitionDefinition<S, E, C>>>();
        for (Map.Entry<S, Map<E, TransitionDefinition<S, E, C>>> entry : transitions.entrySet()) {
            snapshot.put(entry.getKey(),
                    Collections.unmodifiableMap(new LinkedHashMap<E, TransitionDefinition<S, E, C>>(entry.getValue())));
        }
        return new StateMachine<S, E, C>(Collections.unmodifiableMap(snapshot));
    }
}
```

`base/src/main/java/yier/bubu/base/statemachine/StateMachine.java`

```java
package yier.bubu.base.statemachine;

import java.util.Map;
import java.util.Objects;

public final class StateMachine<S extends Enum<S>, E extends Enum<E>, C> {
    private final Map<S, Map<E, TransitionDefinition<S, E, C>>> transitions;

    StateMachine(Map<S, Map<E, TransitionDefinition<S, E, C>>> transitions) {
        this.transitions = Objects.requireNonNull(transitions, "transitions");
    }

    // 状态机本身不保存当前状态，便于多个业务对象复用同一份状态定义。
    public TransitionResult<S, E, C> fire(S sourceState, E event, C context) {
        Objects.requireNonNull(sourceState, "sourceState");
        Objects.requireNonNull(event, "event");

        TransitionDefinition<S, E, C> definition = findDefinition(sourceState, event);
        if (definition == null) {
            return TransitionResult.rejected(
                    sourceState,
                    null,
                    event,
                    context,
                    RejectionReason.NO_TRANSITION_DEFINED);
        }
        return TransitionResult.success(sourceState, definition.getTargetState(), event, context);
    }

    public boolean canFire(S sourceState, E event, C context) {
        Objects.requireNonNull(sourceState, "sourceState");
        Objects.requireNonNull(event, "event");
        return findDefinition(sourceState, event) != null;
    }

    private TransitionDefinition<S, E, C> findDefinition(S sourceState, E event) {
        Map<E, TransitionDefinition<S, E, C>> byEvent = transitions.get(sourceState);
        if (byEvent == null) {
            return null;
        }
        return byEvent.get(event);
    }
}
```

- [ ] **Step 4: Re-run the focused framework test class**

Run: `mvn -pl base -am test -Dtest=StateMachineTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS with `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit the core transition result slice**

```bash
git add base/src/main/java/yier/bubu/base/statemachine/RejectionReason.java \
        base/src/main/java/yier/bubu/base/statemachine/TransitionResult.java \
        base/src/main/java/yier/bubu/base/statemachine/TransitionDefinition.java \
        base/src/main/java/yier/bubu/base/statemachine/StateMachineBuilder.java \
        base/src/main/java/yier/bubu/base/statemachine/StateMachine.java \
        base/src/test/java/yier/bubu/base/statemachine/StateMachineTest.java
git commit -m "feat(base): add core state machine transitions"
```

### Task 2: Add Guard, Action, Listener, and Validation Semantics

**Files:**
- Modify: `base/src/test/java/yier/bubu/base/statemachine/StateMachineTest.java`
- Create: `base/src/main/java/yier/bubu/base/statemachine/TransitionContext.java`
- Create: `base/src/main/java/yier/bubu/base/statemachine/TransitionGuard.java`
- Create: `base/src/main/java/yier/bubu/base/statemachine/TransitionAction.java`
- Create: `base/src/main/java/yier/bubu/base/statemachine/StateMachineListener.java`
- Create: `base/src/main/java/yier/bubu/base/statemachine/package-info.java`
- Modify: `base/src/main/java/yier/bubu/base/statemachine/TransitionDefinition.java`
- Modify: `base/src/main/java/yier/bubu/base/statemachine/StateMachineBuilder.java`
- Modify: `base/src/main/java/yier/bubu/base/statemachine/StateMachine.java`

- [ ] **Step 1: Extend the framework tests with guard, action, listener, and validation expectations**

```java
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

        TransitionResult<SampleState, SampleEvent, SampleContext> result =
                machine.fire(SampleState.CREATED, SampleEvent.CANCEL, new SampleContext());

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals(SampleState.CREATED, result.getSourceState());
        Assert.assertNull(result.getTargetState());
        Assert.assertEquals(SampleEvent.CANCEL, result.getEvent());
        Assert.assertEquals(RejectionReason.NO_TRANSITION_DEFINED, result.getRejectionReason());
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
    public void addTransition_shouldRejectDuplicateDefinition() {
        try {
            new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                    .addTransition(SampleState.CREATED, SampleEvent.PAY, SampleState.PAID)
                    .addTransition(SampleState.CREATED, SampleEvent.PAY, SampleState.CANCELLED);
            Assert.fail("Expected duplicate transition definition to be rejected");
        } catch (IllegalStateException exception) {
            Assert.assertTrue(exception.getMessage().contains("sourceState=CREATED"));
            Assert.assertTrue(exception.getMessage().contains("event=PAY"));
        }
    }

    @Test
    public void build_shouldRejectEmptyDefinition() {
        try {
            new StateMachineBuilder<SampleState, SampleEvent, SampleContext>().build();
            Assert.fail("Expected empty builder to be rejected");
        } catch (IllegalStateException exception) {
            Assert.assertTrue(exception.getMessage().contains("At least one transition"));
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
    public void fire_shouldPropagateListenerFailure() {
        StateMachine<SampleState, SampleEvent, SampleContext> machine =
                new StateMachineBuilder<SampleState, SampleEvent, SampleContext>()
                        .addTransition(SampleState.CREATED, SampleEvent.PAY, SampleState.PAID)
                        .addListener(new StateMachineListener<SampleState, SampleEvent, SampleContext>() {
                            @Override
                            public void onSuccess(
                                    TransitionContext<SampleState, SampleEvent, SampleContext> transitionContext) {
                                throw new IllegalStateException("listener failure");
                            }
                        })
                        .build();

        try {
            machine.fire(SampleState.CREATED, SampleEvent.PAY, new SampleContext());
            Assert.fail("Expected listener failure to propagate");
        } catch (IllegalStateException exception) {
            Assert.assertEquals("listener failure", exception.getMessage());
        }
    }
}
```

- [ ] **Step 2: Run the focused framework test class again to verify the new behaviors are red**

Run: `mvn -pl base -am test -Dtest=StateMachineTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because the builder does not yet support guards/actions/listeners, `TransitionContext` does not exist, and `StateMachine` does not yet apply runtime semantics.

- [ ] **Step 3: Implement guard/action/listener semantics and package documentation**

`base/src/main/java/yier/bubu/base/statemachine/TransitionContext.java`

```java
package yier.bubu.base.statemachine;

import java.util.Objects;

public final class TransitionContext<S extends Enum<S>, E extends Enum<E>, C> {
    private final S sourceState;
    private final S targetState;
    private final E event;
    private final C context;

    public TransitionContext(S sourceState, S targetState, E event, C context) {
        this.sourceState = Objects.requireNonNull(sourceState, "sourceState");
        this.targetState = Objects.requireNonNull(targetState, "targetState");
        this.event = Objects.requireNonNull(event, "event");
        this.context = context;
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
}
```

`base/src/main/java/yier/bubu/base/statemachine/TransitionGuard.java`

```java
package yier.bubu.base.statemachine;

@FunctionalInterface
public interface TransitionGuard<S extends Enum<S>, E extends Enum<E>, C> {
    boolean test(TransitionContext<S, E, C> transitionContext);
}
```

`base/src/main/java/yier/bubu/base/statemachine/TransitionAction.java`

```java
package yier.bubu.base.statemachine;

@FunctionalInterface
public interface TransitionAction<S extends Enum<S>, E extends Enum<E>, C> {
    void execute(TransitionContext<S, E, C> transitionContext);
}
```

`base/src/main/java/yier/bubu/base/statemachine/StateMachineListener.java`

```java
package yier.bubu.base.statemachine;

public interface StateMachineListener<S extends Enum<S>, E extends Enum<E>, C> {
    default void onSuccess(TransitionContext<S, E, C> transitionContext) {
    }

    default void onRejected(S sourceState, E event, C context, RejectionReason rejectionReason) {
    }

    default void onError(TransitionContext<S, E, C> transitionContext, RuntimeException exception) {
    }
}
```

`base/src/main/java/yier/bubu/base/statemachine/package-info.java`

```java
/**
 * 通用状态机组件。
 * <p>
 * 这里的实现是进程内、同步执行、强类型的状态机，不负责持久化当前状态。
 * 调用方在每次触发事件时显式传入当前状态和业务上下文。
 */
package yier.bubu.base.statemachine;
```

`base/src/main/java/yier/bubu/base/statemachine/TransitionDefinition.java`

```java
package yier.bubu.base.statemachine;

import java.util.Objects;

final class TransitionDefinition<S extends Enum<S>, E extends Enum<E>, C> {
    private final S sourceState;
    private final E event;
    private final S targetState;
    private final TransitionGuard<S, E, C> guard;
    private final TransitionAction<S, E, C> action;

    TransitionDefinition(S sourceState,
                         E event,
                         S targetState,
                         TransitionGuard<S, E, C> guard,
                         TransitionAction<S, E, C> action) {
        this.sourceState = Objects.requireNonNull(sourceState, "sourceState");
        this.event = Objects.requireNonNull(event, "event");
        this.targetState = Objects.requireNonNull(targetState, "targetState");
        this.guard = guard;
        this.action = action;
    }

    S getSourceState() {
        return sourceState;
    }

    E getEvent() {
        return event;
    }

    S getTargetState() {
        return targetState;
    }

    TransitionGuard<S, E, C> getGuard() {
        return guard;
    }

    TransitionAction<S, E, C> getAction() {
        return action;
    }
}
```

`base/src/main/java/yier/bubu/base/statemachine/StateMachineBuilder.java`

```java
package yier.bubu.base.statemachine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class StateMachineBuilder<S extends Enum<S>, E extends Enum<E>, C> {
    private final Map<S, Map<E, TransitionDefinition<S, E, C>>> transitions =
            new LinkedHashMap<S, Map<E, TransitionDefinition<S, E, C>>>();
    private final List<StateMachineListener<S, E, C>> listeners =
            new ArrayList<StateMachineListener<S, E, C>>();

    public StateMachineBuilder<S, E, C> addTransition(S from, E event, S to) {
        return addTransition(from, event, to, null, null);
    }

    public StateMachineBuilder<S, E, C> addTransition(
            S from,
            E event,
            S to,
            TransitionGuard<S, E, C> guard) {
        return addTransition(from, event, to, guard, null);
    }

    public StateMachineBuilder<S, E, C> addTransition(
            S from,
            E event,
            S to,
            TransitionGuard<S, E, C> guard,
            TransitionAction<S, E, C> action) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(to, "to");

        Map<E, TransitionDefinition<S, E, C>> byEvent = transitions.get(from);
        if (byEvent == null) {
            byEvent = new LinkedHashMap<E, TransitionDefinition<S, E, C>>();
            transitions.put(from, byEvent);
        }
        if (byEvent.containsKey(event)) {
            throw new IllegalStateException(
                    "Duplicate transition definition for sourceState=" + from + ", event=" + event);
        }

        byEvent.put(event, new TransitionDefinition<S, E, C>(from, event, to, guard, action));
        return this;
    }

    public StateMachineBuilder<S, E, C> addListener(StateMachineListener<S, E, C> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    public StateMachine<S, E, C> build() {
        if (transitions.isEmpty()) {
            throw new IllegalStateException("At least one transition must be defined");
        }

        Map<S, Map<E, TransitionDefinition<S, E, C>>> transitionSnapshot =
                new LinkedHashMap<S, Map<E, TransitionDefinition<S, E, C>>>();
        for (Map.Entry<S, Map<E, TransitionDefinition<S, E, C>>> entry : transitions.entrySet()) {
            transitionSnapshot.put(
                    entry.getKey(),
                    Collections.unmodifiableMap(
                            new LinkedHashMap<E, TransitionDefinition<S, E, C>>(entry.getValue())));
        }

        List<StateMachineListener<S, E, C>> listenerSnapshot =
                Collections.unmodifiableList(new ArrayList<StateMachineListener<S, E, C>>(listeners));

        return new StateMachine<S, E, C>(
                Collections.unmodifiableMap(transitionSnapshot),
                listenerSnapshot);
    }
}
```

`base/src/main/java/yier/bubu/base/statemachine/StateMachine.java`

```java
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

    // 状态机定义是无状态的，当前状态由调用方持有并在每次触发时显式传入。
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
```

- [ ] **Step 4: Re-run the focused framework test class**

Run: `mvn -pl base -am test -Dtest=StateMachineTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS with `Tests run: 11, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit the runtime semantics slice**

```bash
git add base/src/main/java/yier/bubu/base/statemachine/TransitionContext.java \
        base/src/main/java/yier/bubu/base/statemachine/TransitionGuard.java \
        base/src/main/java/yier/bubu/base/statemachine/TransitionAction.java \
        base/src/main/java/yier/bubu/base/statemachine/StateMachineListener.java \
        base/src/main/java/yier/bubu/base/statemachine/package-info.java \
        base/src/main/java/yier/bubu/base/statemachine/TransitionDefinition.java \
        base/src/main/java/yier/bubu/base/statemachine/StateMachineBuilder.java \
        base/src/main/java/yier/bubu/base/statemachine/StateMachine.java \
        base/src/test/java/yier/bubu/base/statemachine/StateMachineTest.java
git commit -m "feat(base): add state machine runtime semantics"
```

### Task 3: Build the Order Workflow Example on Top of the Generic Engine

**Files:**
- Create: `base/src/test/java/yier/bubu/base/statemachine/order/OrderStateMachineTest.java`
- Create: `base/src/main/java/yier/bubu/base/statemachine/order/OrderState.java`
- Create: `base/src/main/java/yier/bubu/base/statemachine/order/OrderEvent.java`
- Create: `base/src/main/java/yier/bubu/base/statemachine/order/OrderContext.java`
- Create: `base/src/main/java/yier/bubu/base/statemachine/order/OrderStateMachineFactory.java`

- [ ] **Step 1: Write the failing order workflow tests that prove the framework is reusable**

```java
package yier.bubu.base.statemachine.order;

import org.junit.Assert;
import org.junit.Test;
import yier.bubu.base.statemachine.RejectionReason;
import yier.bubu.base.statemachine.StateMachine;
import yier.bubu.base.statemachine.TransitionResult;

import java.util.Arrays;

public class OrderStateMachineTest {
    private final StateMachine<OrderState, OrderEvent, OrderContext> machine =
            OrderStateMachineFactory.create();

    @Test
    public void order_shouldFollowHappyPath() {
        OrderContext context = new OrderContext("order-1001");

        TransitionResult<OrderState, OrderEvent, OrderContext> paid =
                machine.fire(OrderState.CREATED, OrderEvent.PAY, context);
        TransitionResult<OrderState, OrderEvent, OrderContext> shipped =
                machine.fire(OrderState.PAID, OrderEvent.SHIP, context);
        TransitionResult<OrderState, OrderEvent, OrderContext> completed =
                machine.fire(OrderState.SHIPPED, OrderEvent.COMPLETE, context);

        Assert.assertTrue(paid.isSuccess());
        Assert.assertTrue(shipped.isSuccess());
        Assert.assertTrue(completed.isSuccess());
        Assert.assertEquals(OrderState.PAID, paid.getTargetState());
        Assert.assertEquals(OrderState.SHIPPED, shipped.getTargetState());
        Assert.assertEquals(OrderState.COMPLETED, completed.getTargetState());
        Assert.assertEquals(100L, context.getPaidAmount());
        Assert.assertTrue(context.hasShipment());
        Assert.assertEquals(Arrays.asList(
                "payment captured",
                "transition:CREATED->PAID by PAY",
                "shipment dispatched",
                "transition:PAID->SHIPPED by SHIP",
                "transition:SHIPPED->COMPLETED by COMPLETE"
        ), context.getAuditLogs());
    }

    @Test
    public void order_shouldAllowRetryAfterPaymentFailure() {
        OrderContext context = new OrderContext("order-1002");

        TransitionResult<OrderState, OrderEvent, OrderContext> failed =
                machine.fire(OrderState.CREATED, OrderEvent.PAYMENT_FAIL, context);
        TransitionResult<OrderState, OrderEvent, OrderContext> retried =
                machine.fire(OrderState.PAYMENT_FAILED, OrderEvent.PAY, context);

        Assert.assertTrue(failed.isSuccess());
        Assert.assertEquals(OrderState.PAYMENT_FAILED, failed.getTargetState());
        Assert.assertTrue(retried.isSuccess());
        Assert.assertEquals(OrderState.PAID, retried.getTargetState());
        Assert.assertEquals(100L, context.getPaidAmount());
        Assert.assertTrue(context.getAuditLogs().contains("transition:CREATED->PAYMENT_FAILED by PAYMENT_FAIL"));
        Assert.assertTrue(context.getAuditLogs().contains("payment captured"));
    }

    @Test
    public void order_shouldAllowCancellationFromCreated() {
        TransitionResult<OrderState, OrderEvent, OrderContext> result =
                machine.fire(OrderState.CREATED, OrderEvent.CANCEL, new OrderContext("order-1003"));

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(OrderState.CANCELLED, result.getTargetState());
    }

    @Test
    public void order_shouldSupportRefundFlow() {
        OrderContext context = new OrderContext("order-1004");

        machine.fire(OrderState.CREATED, OrderEvent.PAY, context);
        TransitionResult<OrderState, OrderEvent, OrderContext> refunding =
                machine.fire(OrderState.PAID, OrderEvent.APPLY_REFUND, context);
        TransitionResult<OrderState, OrderEvent, OrderContext> refunded =
                machine.fire(OrderState.REFUNDING, OrderEvent.REFUND_SUCCESS, context);

        Assert.assertTrue(refunding.isSuccess());
        Assert.assertEquals(OrderState.REFUNDING, refunding.getTargetState());
        Assert.assertTrue(refunded.isSuccess());
        Assert.assertEquals(OrderState.REFUNDED, refunded.getTargetState());
        Assert.assertTrue(context.isRefundRequested());
        Assert.assertEquals(Arrays.asList(
                "payment captured",
                "transition:CREATED->PAID by PAY",
                "refund requested",
                "transition:PAID->REFUNDING by APPLY_REFUND",
                "transition:REFUNDING->REFUNDED by REFUND_SUCCESS"
        ), context.getAuditLogs());
    }

    @Test
    public void order_shouldRejectIllegalTransition() {
        TransitionResult<OrderState, OrderEvent, OrderContext> result =
                machine.fire(OrderState.CREATED, OrderEvent.SHIP, new OrderContext("order-1005"));

        Assert.assertFalse(result.isSuccess());
        Assert.assertNull(result.getTargetState());
        Assert.assertEquals(RejectionReason.NO_TRANSITION_DEFINED, result.getRejectionReason());
    }

    @Test
    public void order_shouldRejectShippingWhenPaidAmountIsMissing() {
        OrderContext context = new OrderContext("order-1006");

        TransitionResult<OrderState, OrderEvent, OrderContext> result =
                machine.fire(OrderState.PAID, OrderEvent.SHIP, context);

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals(RejectionReason.GUARD_REJECTED, result.getRejectionReason());
        Assert.assertFalse(context.hasShipment());
    }
}
```

- [ ] **Step 2: Run the order workflow tests to verify they are red**

Run: `mvn -pl base -am test -Dtest=OrderStateMachineTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because the order enums, context, and factory do not exist yet.

- [ ] **Step 3: Implement the order example using the generic framework**

`base/src/main/java/yier/bubu/base/statemachine/order/OrderState.java`

```java
package yier.bubu.base.statemachine.order;

public enum OrderState {
    CREATED,
    PAID,
    PAYMENT_FAILED,
    SHIPPED,
    COMPLETED,
    CANCELLED,
    REFUNDING,
    REFUNDED
}
```

`base/src/main/java/yier/bubu/base/statemachine/order/OrderEvent.java`

```java
package yier.bubu.base.statemachine.order;

public enum OrderEvent {
    PAY,
    PAYMENT_FAIL,
    SHIP,
    COMPLETE,
    CANCEL,
    APPLY_REFUND,
    REFUND_SUCCESS
}
```

`base/src/main/java/yier/bubu/base/statemachine/order/OrderContext.java`

```java
package yier.bubu.base.statemachine.order;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class OrderContext {
    private final String orderId;
    private long paidAmount;
    private boolean hasShipment;
    private boolean refundRequested;
    private final List<String> auditLogs = new ArrayList<String>();

    public OrderContext(String orderId) {
        this.orderId = Objects.requireNonNull(orderId, "orderId");
    }

    public String getOrderId() {
        return orderId;
    }

    public long getPaidAmount() {
        return paidAmount;
    }

    public boolean hasShipment() {
        return hasShipment;
    }

    public boolean isRefundRequested() {
        return refundRequested;
    }

    public List<String> getAuditLogs() {
        return Collections.unmodifiableList(auditLogs);
    }

    public void markPaid(long amount) {
        this.paidAmount = amount;
    }

    public void markShipped() {
        this.hasShipment = true;
    }

    public void markRefundRequested() {
        this.refundRequested = true;
    }

    public void addAuditLog(String message) {
        auditLogs.add(Objects.requireNonNull(message, "message"));
    }
}
```

`base/src/main/java/yier/bubu/base/statemachine/order/OrderStateMachineFactory.java`

```java
package yier.bubu.base.statemachine.order;

import yier.bubu.base.statemachine.StateMachine;
import yier.bubu.base.statemachine.StateMachineBuilder;

public final class OrderStateMachineFactory {
    private static final long DEMO_PAID_AMOUNT = 100L;

    private OrderStateMachineFactory() {
    }

    public static StateMachine<OrderState, OrderEvent, OrderContext> create() {
        return new StateMachineBuilder<OrderState, OrderEvent, OrderContext>()
                .addTransition(
                        OrderState.CREATED,
                        OrderEvent.PAY,
                        OrderState.PAID,
                        null,
                        transitionContext -> {
                            // 示例里用固定金额模拟支付入账，真实系统应由支付结果回填金额。
                            transitionContext.getContext().markPaid(DEMO_PAID_AMOUNT);
                            transitionContext.getContext().addAuditLog("payment captured");
                        })
                .addTransition(OrderState.CREATED, OrderEvent.PAYMENT_FAIL, OrderState.PAYMENT_FAILED)
                .addTransition(OrderState.CREATED, OrderEvent.CANCEL, OrderState.CANCELLED)
                .addTransition(
                        OrderState.PAYMENT_FAILED,
                        OrderEvent.PAY,
                        OrderState.PAID,
                        null,
                        transitionContext -> {
                            transitionContext.getContext().markPaid(DEMO_PAID_AMOUNT);
                            transitionContext.getContext().addAuditLog("payment captured");
                        })
                .addTransition(OrderState.PAYMENT_FAILED, OrderEvent.CANCEL, OrderState.CANCELLED)
                .addTransition(
                        OrderState.PAID,
                        OrderEvent.SHIP,
                        OrderState.SHIPPED,
                        transitionContext -> transitionContext.getContext().getPaidAmount() > 0L,
                        transitionContext -> {
                            transitionContext.getContext().markShipped();
                            transitionContext.getContext().addAuditLog("shipment dispatched");
                        })
                .addTransition(
                        OrderState.SHIPPED,
                        OrderEvent.COMPLETE,
                        OrderState.COMPLETED,
                        transitionContext -> transitionContext.getContext().hasShipment())
                .addTransition(
                        OrderState.PAID,
                        OrderEvent.APPLY_REFUND,
                        OrderState.REFUNDING,
                        null,
                        transitionContext -> {
                            transitionContext.getContext().markRefundRequested();
                            transitionContext.getContext().addAuditLog("refund requested");
                        })
                .addTransition(
                        OrderState.COMPLETED,
                        OrderEvent.APPLY_REFUND,
                        OrderState.REFUNDING,
                        null,
                        transitionContext -> {
                            transitionContext.getContext().markRefundRequested();
                            transitionContext.getContext().addAuditLog("refund requested");
                        })
                .addTransition(OrderState.REFUNDING, OrderEvent.REFUND_SUCCESS, OrderState.REFUNDED)
                .addListener(new yier.bubu.base.statemachine.StateMachineListener<OrderState, OrderEvent, OrderContext>() {
                    @Override
                    public void onSuccess(
                            yier.bubu.base.statemachine.TransitionContext<OrderState, OrderEvent, OrderContext> transitionContext) {
                        transitionContext.getContext().addAuditLog(
                                "transition:" + transitionContext.getSourceState()
                                        + "->" + transitionContext.getTargetState()
                                        + " by " + transitionContext.getEvent());
                    }
                })
                .build();
    }
}
```

- [ ] **Step 4: Run the order workflow tests again**

Run: `mvn -pl base -am test -Dtest=OrderStateMachineTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS with `Tests run: 6, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit the order workflow example**

```bash
git add base/src/main/java/yier/bubu/base/statemachine/order/OrderState.java \
        base/src/main/java/yier/bubu/base/statemachine/order/OrderEvent.java \
        base/src/main/java/yier/bubu/base/statemachine/order/OrderContext.java \
        base/src/main/java/yier/bubu/base/statemachine/order/OrderStateMachineFactory.java \
        base/src/test/java/yier/bubu/base/statemachine/order/OrderStateMachineTest.java
git commit -m "feat(base): add order state machine example"
```

### Task 4: Run the Final Focused Regression for the `base` Module

**Files:**
- Verify: `base/src/test/java/yier/bubu/base/HelloBaseTest.java`
- Verify: `base/src/test/java/yier/bubu/base/statemachine/StateMachineTest.java`
- Verify: `base/src/test/java/yier/bubu/base/statemachine/order/OrderStateMachineTest.java`

- [ ] **Step 1: Run the full `base` module test suite**

Run: `mvn -pl base -am test`

Expected: PASS with all `base` module tests green, including `HelloBaseTest`, `StateMachineTest`, and `OrderStateMachineTest`.

- [ ] **Step 2: Inspect the worktree before any final handoff**

Run: `git status --short`

Expected: no unexpected modified files beyond the planned state machine implementation work.

- [ ] **Step 3: Commit the final verified state if the branch still has uncommitted implementation work**

```bash
git add base/src/main/java/yier/bubu/base/statemachine \
        base/src/main/java/yier/bubu/base/statemachine/order \
        base/src/test/java/yier/bubu/base/statemachine \
        base/src/test/java/yier/bubu/base/statemachine/order
git commit -m "test(base): verify state machine implementation"
```

If there is nothing left to commit because Tasks 1-3 already produced clean commits, skip this step and keep the worktree clean.
