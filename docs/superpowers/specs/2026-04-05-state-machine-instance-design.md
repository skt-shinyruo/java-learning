# State Machine Instance Design

## Background

The repository already contains a reusable, strongly typed, in-process state machine framework under
`base/src/main/java/yier/bubu/base/statemachine`. Its current design intentionally keeps
`StateMachine` stateless: callers pass the current state, event, and business context into each
transition attempt.

That design is the right default for reusable rule definitions, but some callers also need a
lightweight runtime wrapper that owns:

- the current state of one specific business object
- the business context associated with that object

The new work should add that wrapper without weakening the existing stateless framework design.

## Goals

- Add a small `StateMachineInstance` type that stores `currentState` and `context`
- Keep the existing `StateMachine` API and semantics intact
- Make successful `fire(event)` calls update the instance's internal state automatically
- Make rejected transitions leave the internal state unchanged
- Make runtime exceptions from guards/actions/listeners leave the internal state unchanged
- Keep the wrapper Java 8 compatible, JDK-only, and easy to understand
- Demonstrate that the wrapper works both in isolation and with the existing order example

## Non-Goals

- No thread-safety or internal locking in the instance wrapper
- No persistence, reload, snapshot serialization, or database integration
- No history tracking or transition journal in the wrapper
- No `setCurrentState(...)`, `reset(...)`, or other rule-bypassing mutation API
- No change to `StateMachineBuilder`, `TransitionResult`, or order workflow rules

## Placement

Production code:

- `base/src/main/java/yier/bubu/base/statemachine/StateMachineInstance.java`

Tests:

- `base/src/test/java/yier/bubu/base/statemachine/StateMachineInstanceTest.java`
- `base/src/test/java/yier/bubu/base/statemachine/order/OrderStateMachineInstanceTest.java`

This keeps the wrapper in the generic state machine package while keeping order-specific proof in
the order topic package.

## Architecture Overview

The existing `StateMachine<S, E, C>` remains the immutable rule engine. The new
`StateMachineInstance<S, E, C>` is a thin runtime holder around one `StateMachine` plus one
specific `(currentState, context)` pair.

This separation should stay explicit:

- `StateMachine` answers: “Given this current state and event, what should happen?”
- `StateMachineInstance` answers: “For this concrete object, what is the current state right now,
  and how does it move when an event happens?”

The wrapper should delegate all rule evaluation to the existing `StateMachine`. It must not
re-implement transition logic.

## Main Type

### `StateMachineInstance<S extends Enum<S>, E extends Enum<E>, C>`

Purpose:

- hold one current state and one business context
- delegate transition decisions to the existing stateless `StateMachine`
- update the held state only after a successful transition

Suggested structure:

- `StateMachine<S, E, C> stateMachine`
- `S currentState`
- `C context`

Proposed public API:

- `StateMachineInstance(StateMachine<S, E, C> stateMachine, S initialState, C context)`
- `S getCurrentState()`
- `C getContext()`
- `boolean canFire(E event)`
- `TransitionResult<S, E, C> fire(E event)`

Design notes:

- the constructor should reject `null` `stateMachine` and `null` `initialState`
- `context` may remain nullable if the underlying business model allows it
- the class should be `final`
- the instance is intentionally not thread-safe; concurrent access remains the caller's
  responsibility

## Runtime Semantics

### `canFire(E event)`

Behavior:

- delegates to `stateMachine.canFire(currentState, event, context)`
- does not modify `currentState`
- does not modify `context`
- propagates runtime exceptions from the underlying state machine unchanged

### `fire(E event)`

Behavior:

1. delegate to `stateMachine.fire(currentState, event, context)`
2. if the returned `TransitionResult` is successful, assign
   `currentState = result.getTargetState()`
3. if the returned `TransitionResult` is rejected, keep `currentState` unchanged
4. if the delegate call throws, keep `currentState` unchanged and rethrow the exception

This gives the wrapper one simple invariant:

- the internal `currentState` only changes after a successful transition result

## Error Handling

The wrapper should be intentionally thin and predictable:

- `null` `event` should fail fast through the delegated state machine call
- rejected transitions should return the same rejected `TransitionResult` the stateless machine
  produced
- runtime transition failures should propagate exactly as the stateless machine reports them

The wrapper must not swallow or reinterpret transition exceptions, because the lower-level
`StateMachine` already owns those semantics.

## Comments and Documentation

Add a small Chinese comment in `StateMachineInstance` explaining the responsibility split:

- the instance holds one object's current state and context
- the stateless `StateMachine` still only holds transition definitions

Avoid redundant comments elsewhere.

## Testing Strategy

### `StateMachineInstanceTest`

The generic wrapper tests should cover:

- constructor stores initial state and context correctly
- `canFire(event)` reflects the underlying machine's decision based on the internal state
- successful `fire(event)` updates `currentState`
- rejected `fire(event)` leaves `currentState` unchanged
- exceptional `fire(event)` leaves `currentState` unchanged
- `getContext()` returns the same context reference passed to the constructor

Use a small local sample state/event/context set in the test class rather than the order example.

### `OrderStateMachineInstanceTest`

The order integration test should prove the wrapper is useful in a real business flow:

- create a `StateMachineInstance<OrderState, OrderEvent, OrderContext>` with initial state
  `CREATED`
- call `fire(PAY)`, `fire(SHIP)`, and `fire(COMPLETE)` without manually passing the current state
- assert that `getCurrentState()` advances to `PAID`, then `SHIPPED`, then `COMPLETED`
- assert that the shared `OrderContext` still accumulates the expected audit logs and business
  fields

Keep this order-instance proof compact. The full workflow coverage already exists in the stateless
order tests.

## Implementation Constraints

- Java 8 compatible
- JDK-only
- no changes to existing order transition rules
- no changes to the existing stateless `StateMachine` public API unless truly necessary
- keep the wrapper focused; do not add history, synchronization, or mutation shortcuts

## Planned Files

- `base/src/main/java/yier/bubu/base/statemachine/StateMachineInstance.java`
- `base/src/test/java/yier/bubu/base/statemachine/StateMachineInstanceTest.java`
- `base/src/test/java/yier/bubu/base/statemachine/order/OrderStateMachineInstanceTest.java`
