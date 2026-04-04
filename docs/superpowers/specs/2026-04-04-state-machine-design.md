# State Machine Design

## Background

This repository is a multi-module Java learning project with focused, dependency-light examples in
each module. The `base` module currently contains small reusable utilities and simple JUnit tests,
which makes it the most natural place for a general-purpose state machine component that does not
depend on concurrency, network, Redis, or JVM-internals topics.

The new work should add:

- a production-usable, in-process, reusable state machine framework in Java
- a business example built on top of the framework for order lifecycle transitions
- focused JUnit tests that act as executable documentation
- necessary Chinese comments in places where the design intent or runtime semantics are not obvious

The implementation should stay JDK-only, Java 8 compatible, and aligned with the repository's
existing style of small classes plus clear tests.

## Goals

- Place a reusable state machine framework in the `base` module
- Use strong typing for state and event definitions through Java generics and `enum`-friendly APIs
- Support table-driven transition registration with builder-based construction
- Support synchronous guard evaluation, transition actions, and lifecycle listeners
- Return explicit rejection results for missing transitions and guard failures
- Throw clear runtime exceptions for action execution failures
- Keep the built `StateMachine` immutable and safe to reuse across threads
- Demonstrate the framework with an order lifecycle example that includes both happy-path and
  exceptional flows
- Add Chinese comments where they materially improve maintainability or explain tricky semantics

## Non-Goals

- No persistence, state recovery, or workflow-engine orchestration
- No asynchronous action execution, thread pools, or callback choreography
- No hierarchical states, parallel regions, timers, or saga-like compensation
- No Spring integration, annotation scanning, or reflection-based auto-registration
- No distributed locking, idempotency, or database transaction management
- No generic BPMN/process engine abstraction

## Placement

The implementation should live in the `base` module.

Framework package:

- `base/src/main/java/yier/bubu/base/statemachine`

Order example package:

- `base/src/main/java/yier/bubu/base/statemachine/order`

Tests:

- `base/src/test/java/yier/bubu/base/statemachine`
- `base/src/test/java/yier/bubu/base/statemachine/order`

This placement keeps the framework dependency-light and reusable by any future example in the
repository.

## Architecture Overview

The framework should be table-driven. A `StateMachineBuilder` collects transition definitions keyed
by `(sourceState, event)` and produces an immutable `StateMachine`. The `StateMachine` itself does
not store the current state; callers pass the current state, event, and business context into each
execution. This keeps the engine focused on transition rules rather than persistence or object
lifecycle.

One transition definition maps:

- source state
- event
- target state
- optional guard
- optional action

Listeners observe success, rejection, and error events. The framework should differentiate between
business rejection and runtime failure:

- business rejection: the transition is rejected by missing rule or guard check, return
  `TransitionResult`
- runtime failure: action throws, propagate an exception with enough context for troubleshooting

## Main Types

### `StateMachine<S extends Enum<S>, E extends Enum<E>, C>`

Purpose:

- execute transitions against an immutable rule table
- expose the minimum API needed for reusable in-process state transitions

Proposed public API:

- `TransitionResult<S, E, C> fire(S sourceState, E event, C context)`
- `boolean canFire(S sourceState, E event, C context)`

Design notes:

- `fire(...)` evaluates rule lookup, guard, action, and listeners in one synchronous call
- `canFire(...)` uses the same rule and guard logic as `fire(...)` but must not execute actions or
  listeners
- the class should be immutable after construction and therefore reusable across threads
- the class must reject `null` source state and `null` event with clear exceptions
- `context` may be nullable at the framework level unless the specific business model disallows it

### `StateMachineBuilder<S extends Enum<S>, E extends Enum<E>, C>`

Purpose:

- collect transition rules in a readable, strongly typed way
- validate duplicate or malformed definitions before the machine is built

Proposed API shape:

- `addTransition(S from, E event, S to)`
- `addTransition(S from, E event, S to, TransitionGuard<S, E, C> guard)`
- `addTransition(S from, E event, S to, TransitionGuard<S, E, C> guard, TransitionAction<S, E, C> action)`
- `addListener(StateMachineListener<S, E, C> listener)`
- `StateMachine<S, E, C> build()`

Validation rules:

- each `(from, event)` pair may appear only once
- `from`, `to`, and `event` must be non-null
- listeners must be non-null
- build should fail fast with `IllegalStateException` or `IllegalArgumentException` when the
  definition is invalid

### `TransitionDefinition<S, E, C>`

Purpose:

- represent one immutable transition rule in the built machine

Contents:

- `sourceState`
- `event`
- `targetState`
- `guard`
- `action`

This type may stay package-private if the public API does not need to expose rule internals.

### `TransitionContext<S, E, C>`

Purpose:

- carry runtime information for guard, action, and listener execution

Contents:

- `sourceState`
- `targetState`
- `event`
- `context`

The framework should create a fresh `TransitionContext` for each `fire(...)` call.

### `TransitionResult<S, E, C>`

Purpose:

- report whether a transition succeeded or was rejected

Suggested fields:

- `boolean success`
- `S sourceState`
- `S targetState`
- `E event`
- `C context`
- `RejectionReason rejectionReason`

Suggested rejection reasons:

- `NO_TRANSITION_DEFINED`
- `GUARD_REJECTED`

Design notes:

- success results should carry both source and target state
- rejected results should carry source state, event, and context, and may leave target state as
  `null`
- action exceptions are not encoded as a rejection reason because they are runtime failures, not
  business rejections

### `TransitionGuard<S, E, C>`

Purpose:

- decide whether a matched transition is allowed to proceed

Contract:

- returns `true` to allow the transition
- returns `false` to reject the transition
- should be side-effect free because `canFire(...)` may execute it

### `TransitionAction<S, E, C>`

Purpose:

- run synchronous business logic as part of a successful transition

Contract:

- executes after the guard passes and before a success result is returned
- may mutate the supplied business context
- if it throws, the framework should wrap or rethrow a runtime exception enriched with source
  state, target state, and event information

### `StateMachineListener<S, E, C>`

Purpose:

- observe transition lifecycle events without coupling business code into the engine core

Suggested callbacks:

- `onSuccess(TransitionContext<S, E, C> transitionContext)`
- `onRejected(S sourceState, E event, C context, RejectionReason rejectionReason)`
- `onError(TransitionContext<S, E, C> transitionContext, RuntimeException exception)`

Listener failures should not silently disappear. A simple and production-reasonable rule for this
task is:

- all listener callbacks execute synchronously
- if a listener throws, propagate the runtime exception to the caller

## Execution Semantics

The execution flow for `fire(...)` should be:

1. validate required inputs
2. look up the transition definition by `(sourceState, event)`
3. if no rule exists, notify rejection listeners and return a rejected result
4. build a `TransitionContext`
5. evaluate the guard if present
6. if guard returns `false`, notify rejection listeners and return a rejected result
7. execute the action if present
8. if action succeeds, notify success listeners and return a success result
9. if action throws, notify error listeners and propagate the runtime exception

The framework must make the guard-vs-action distinction explicit in code and comments:

- guard failure means "business rule not satisfied"
- action exception means "execution failed after the transition was allowed"

This semantic distinction is important enough to justify Chinese comments at the relevant execution
branch.

## Thread Safety

Thread safety should be documented narrowly and honestly:

- built `StateMachine` instances are thread-safe for concurrent reuse because they are immutable
- `StateMachineBuilder` is not thread-safe
- business `context` objects passed into `fire(...)` are not made thread-safe by the framework
- if callers share mutable business context across threads, synchronization remains the caller's
  responsibility

## Order Example

The order example should prove that the framework supports mainline flows, exceptional flows,
guards, actions, and listeners without introducing persistence concerns.

### `OrderState`

Suggested states:

- `CREATED`
- `PAID`
- `PAYMENT_FAILED`
- `SHIPPED`
- `COMPLETED`
- `CANCELLED`
- `REFUNDING`
- `REFUNDED`

### `OrderEvent`

Suggested events:

- `PAY`
- `PAYMENT_FAIL`
- `SHIP`
- `COMPLETE`
- `CANCEL`
- `APPLY_REFUND`
- `REFUND_SUCCESS`

### `OrderContext`

Suggested fields:

- `String orderId`
- `long paidAmount`
- `boolean hasShipment`
- `boolean refundRequested`
- `List<String> auditLogs`

Purpose:

- carry the business facts that guards and actions need
- make test expectations explicit and readable

### Transition Rules

The default order state machine should register these rules:

- `CREATED + PAY -> PAID`
- `CREATED + PAYMENT_FAIL -> PAYMENT_FAILED`
- `CREATED + CANCEL -> CANCELLED`
- `PAYMENT_FAILED + PAY -> PAID`
- `PAYMENT_FAILED + CANCEL -> CANCELLED`
- `PAID + SHIP -> SHIPPED`
- `PAID + APPLY_REFUND -> REFUNDING`
- `SHIPPED + COMPLETE -> COMPLETED`
- `COMPLETED + APPLY_REFUND -> REFUNDING`
- `REFUNDING + REFUND_SUCCESS -> REFUNDED`

### Guard and Action Examples

Representative business logic should include:

- `SHIP` requires `paidAmount > 0`
- `COMPLETE` requires `hasShipment == true`
- `APPLY_REFUND` should set `refundRequested = true` through an action
- successful payment and shipping should append audit log messages

The example should stay intentionally small. It is a demonstrator for the framework, not a full
order domain model.

### Factory or Assembly Type

Create a dedicated assembly type for the example, such as:

- `OrderStateMachineFactory`

Purpose:

- build the default order state machine in one place
- keep order-specific rule registration out of the generic framework package

## Error Handling

Error handling expectations should be explicit:

- invalid builder configuration fails fast during `build()`
- `null` source state and `null` event fail fast in runtime APIs
- missing transition returns a rejected result rather than throwing
- guard rejection returns a rejected result rather than throwing
- action failures throw runtime exceptions with contextual information
- listener failures also propagate runtime exceptions

For action/listener failures, the error message should include at least:

- source state
- target state when available
- event

This is enough context for logs and troubleshooting without overengineering the exception model.

## Testing Strategy

### `StateMachineTest`

The framework tests should act as executable documentation and cover:

- a basic successful transition
- missing rule returns `NO_TRANSITION_DEFINED`
- guard rejection returns `GUARD_REJECTED`
- guard rejection does not execute action
- action can mutate business context on success
- action failure propagates a runtime exception
- `canFire(...)` reflects the same rule and guard decisions as `fire(...)`
- duplicate `(from, event)` registration fails during build
- listeners are invoked on success and rejection

### `OrderStateMachineTest`

The order example tests should cover:

- `CREATED -> PAID -> SHIPPED -> COMPLETED` happy path
- `CREATED -> PAYMENT_FAILED -> PAID` retry payment path
- `CREATED -> CANCELLED` cancellation path
- `PAID -> REFUNDING -> REFUNDED` refund path
- illegal transitions such as `CREATED + SHIP` are rejected
- guard failures such as shipping without paid amount are rejected
- actions update `OrderContext` and audit logs as expected

The tests should remain deterministic and avoid time, thread, or external-system dependencies.

## Documentation and Comments

The implementation should include:

- `package-info.java` for `yier.bubu.base.statemachine`
- necessary Chinese comments in the engine execution path and order example

Comment guidance:

- explain why the engine itself is stateless and reusable
- explain why guard rejection returns a normal result while action failure throws
- avoid redundant comments that merely restate obvious code

## Planned Files

- `base/src/main/java/yier/bubu/base/statemachine/StateMachine.java`
- `base/src/main/java/yier/bubu/base/statemachine/StateMachineBuilder.java`
- `base/src/main/java/yier/bubu/base/statemachine/TransitionDefinition.java`
- `base/src/main/java/yier/bubu/base/statemachine/TransitionContext.java`
- `base/src/main/java/yier/bubu/base/statemachine/TransitionResult.java`
- `base/src/main/java/yier/bubu/base/statemachine/TransitionGuard.java`
- `base/src/main/java/yier/bubu/base/statemachine/TransitionAction.java`
- `base/src/main/java/yier/bubu/base/statemachine/StateMachineListener.java`
- `base/src/main/java/yier/bubu/base/statemachine/RejectionReason.java`
- `base/src/main/java/yier/bubu/base/statemachine/package-info.java`
- `base/src/main/java/yier/bubu/base/statemachine/order/OrderState.java`
- `base/src/main/java/yier/bubu/base/statemachine/order/OrderEvent.java`
- `base/src/main/java/yier/bubu/base/statemachine/order/OrderContext.java`
- `base/src/main/java/yier/bubu/base/statemachine/order/OrderStateMachineFactory.java`
- `base/src/test/java/yier/bubu/base/statemachine/StateMachineTest.java`
- `base/src/test/java/yier/bubu/base/statemachine/order/OrderStateMachineTest.java`
