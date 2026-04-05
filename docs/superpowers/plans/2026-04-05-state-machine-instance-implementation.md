# State Machine Instance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a small `StateMachineInstance` wrapper that stores `currentState` and `context`, then prove it works both generically and with the existing order workflow.

**Architecture:** Keep the existing `StateMachine` stateless and immutable; add a separate `StateMachineInstance` that delegates rule evaluation to it while owning one runtime `(currentState, context)` pair. The wrapper will update internal state only after successful transitions, leave state unchanged on rejection or exception, and stay intentionally single-threaded and minimal.

**Tech Stack:** Java 8, Maven, JUnit 4, JDK collections and utilities only

---

## File Structure

- Create: `base/src/main/java/yier/bubu/base/statemachine/StateMachineInstance.java`
  Responsibility: hold one current state and one context, delegate transition evaluation to the existing stateless `StateMachine`, and update internal state only after successful transitions.
- Create: `base/src/test/java/yier/bubu/base/statemachine/StateMachineInstanceTest.java`
  Responsibility: executable documentation for generic wrapper behavior such as constructor validation, state advancement, rejection handling, and exception handling.
- Create: `base/src/test/java/yier/bubu/base/statemachine/order/OrderStateMachineInstanceTest.java`
  Responsibility: compact proof that the wrapper works with the existing order example without manually passing the current state on every call.

## Preflight

- Work in the current workspace and do not revert unrelated changes.
- Follow TDD strictly: write the failing tests first, run them to verify red, then add the minimal implementation, then rerun green.
- Do not modify the existing stateless `StateMachine` public API or the existing order transition rules.
- Leave the unrelated untracked plan file `docs/superpowers/plans/2026-04-04-state-machine-implementation.md` untouched.

### Task 1: Add the Stateful Wrapper and Its Focused Tests

**Files:**
- Create: `base/src/main/java/yier/bubu/base/statemachine/StateMachineInstance.java`
- Create: `base/src/test/java/yier/bubu/base/statemachine/StateMachineInstanceTest.java`
- Create: `base/src/test/java/yier/bubu/base/statemachine/order/OrderStateMachineInstanceTest.java`

- [ ] **Step 1: Write the failing wrapper and order-integration tests**

`base/src/test/java/yier/bubu/base/statemachine/StateMachineInstanceTest.java`

```java
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
```

`base/src/test/java/yier/bubu/base/statemachine/order/OrderStateMachineInstanceTest.java`

```java
package yier.bubu.base.statemachine.order;

import org.junit.Assert;
import org.junit.Test;
import yier.bubu.base.statemachine.StateMachineInstance;
import yier.bubu.base.statemachine.TransitionResult;

import java.util.Arrays;

public class OrderStateMachineInstanceTest {
    @Test
    public void fire_shouldAdvanceOrderStateWithoutPassingCurrentStateManually() {
        OrderContext context = new OrderContext("ORDER-200");
        StateMachineInstance<OrderState, OrderEvent, OrderContext> instance =
                new StateMachineInstance<OrderState, OrderEvent, OrderContext>(
                        OrderStateMachineFactory.create(),
                        OrderState.CREATED,
                        context);

        TransitionResult<OrderState, OrderEvent, OrderContext> payResult = instance.fire(OrderEvent.PAY);
        Assert.assertTrue(payResult.isSuccess());
        Assert.assertEquals(OrderState.PAID, instance.getCurrentState());

        TransitionResult<OrderState, OrderEvent, OrderContext> shipResult = instance.fire(OrderEvent.SHIP);
        Assert.assertTrue(shipResult.isSuccess());
        Assert.assertEquals(OrderState.SHIPPED, instance.getCurrentState());

        TransitionResult<OrderState, OrderEvent, OrderContext> completeResult = instance.fire(OrderEvent.COMPLETE);
        Assert.assertTrue(completeResult.isSuccess());
        Assert.assertEquals(OrderState.COMPLETED, instance.getCurrentState());

        Assert.assertSame(context, instance.getContext());
        Assert.assertEquals(100L, context.getPaidAmount());
        Assert.assertTrue(context.hasShipment());
        Assert.assertEquals(
                Arrays.asList(
                        "payment captured",
                        "transition:CREATED->PAID by PAY",
                        "shipment dispatched",
                        "transition:PAID->SHIPPED by SHIP",
                        "transition:SHIPPED->COMPLETED by COMPLETE"),
                context.getAuditLogs());
    }
}
```

- [ ] **Step 2: Run the focused wrapper tests to verify they are red**

Run: `mvn -pl base -am test -Dtest=StateMachineInstanceTest,OrderStateMachineInstanceTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because `StateMachineInstance` does not exist yet.

- [ ] **Step 3: Implement the minimal wrapper**

`base/src/main/java/yier/bubu/base/statemachine/StateMachineInstance.java`

```java
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
```

- [ ] **Step 4: Re-run the focused wrapper tests**

Run: `mvn -pl base -am test -Dtest=StateMachineInstanceTest,OrderStateMachineInstanceTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS with `Tests run: 8, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit the wrapper slice**

```bash
git add base/src/main/java/yier/bubu/base/statemachine/StateMachineInstance.java \
        base/src/test/java/yier/bubu/base/statemachine/StateMachineInstanceTest.java \
        base/src/test/java/yier/bubu/base/statemachine/order/OrderStateMachineInstanceTest.java
git commit -m "feat(base): add state machine instance wrapper"
```

### Task 2: Run the Full `base` Module Regression

**Files:**
- Verify: `base/src/test/java/yier/bubu/base/HelloBaseTest.java`
- Verify: `base/src/test/java/yier/bubu/base/statemachine/StateMachineTest.java`
- Verify: `base/src/test/java/yier/bubu/base/statemachine/StateMachineInstanceTest.java`
- Verify: `base/src/test/java/yier/bubu/base/statemachine/order/OrderStateMachineTest.java`
- Verify: `base/src/test/java/yier/bubu/base/statemachine/order/OrderStateMachineInstanceTest.java`

- [ ] **Step 1: Run the full `base` module test suite**

Run: `mvn -pl base -am test`

Expected: PASS with `Tests run: 39, Failures: 0, Errors: 0`.

- [ ] **Step 2: Inspect the worktree after verification**

Run: `git status --short`

Expected: no unexpected modified files beyond the pre-existing untracked plan file `docs/superpowers/plans/2026-04-04-state-machine-implementation.md`.

- [ ] **Step 3: Commit any final verification-only changes if the implementation task left tracked files uncommitted**

```bash
git add base/src/main/java/yier/bubu/base/statemachine/StateMachineInstance.java \
        base/src/test/java/yier/bubu/base/statemachine/StateMachineInstanceTest.java \
        base/src/test/java/yier/bubu/base/statemachine/order/OrderStateMachineInstanceTest.java
git commit -m "test(base): verify state machine instance wrapper"
```

If `git status --short` shows no tracked changes after Step 2, skip this step and keep the worktree clean.
