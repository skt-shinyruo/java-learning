# Hierarchical Timing Wheel Scheduler Design

## Background

This repository is a multi-module Java learning project (JDK 8 compatible) with small, readable,
dependency-light examples. The `concurrency` module already contains multiple rate limiting
algorithms and JUnit tests that act as executable documentation. A time wheel (timing wheel)
scheduler is a natural addition to the same module because it is primarily a concurrency + data
structure topic.

This design adds a production-usable (single-process, in-memory) timer scheduler based on a
**hierarchical timing wheel**.

## Goals

- Provide a correct and readable hierarchical timing wheel implementation (Java 8, pure JDK).
- Support:
  - one-shot delayed tasks
  - periodic tasks: `fixed-rate` and `fixed-delay`
- Ensure a periodic task instance is **non-overlapping** (never concurrently executing the same
  periodic task).
- Timer thread only does scheduling; due tasks are executed by a provided `Executor`.
- Provide `cancel()` and a clear shutdown lifecycle.
- Provide deterministic, fast unit tests without `Thread.sleep` (manual clock + `drain()`).

## Non-Goals

- No persistence, clustering, distributed timers, or clock synchronization.
- No cron expressions.
- No priority / fairness among tasks beyond "not before deadline".
- No absolute wall-clock scheduling (`Instant`); we use monotonic time (`System.nanoTime`).

## Placement

Module: `concurrency`

Packages:

- Main code: `concurrency/src/main/java/yier/bubu/concurrency/timewheel`
- Tests: `concurrency/src/test/java/yier/bubu/concurrency/timewheel`

## Public API

Primary type: `TimingWheelScheduler` (name can be adjusted during implementation if the module has
existing naming conventions).

```java
public final class TimingWheelScheduler implements AutoCloseable {
    public TimingWheelScheduler(Duration tickDuration, int wheelSize, Executor taskExecutor);

    public ScheduledTask schedule(Runnable task, Duration delay);

    public ScheduledTask scheduleAtFixedRate(
            Runnable task, Duration initialDelay, Duration period);

    public ScheduledTask scheduleWithFixedDelay(
            Runnable task, Duration initialDelay, Duration delay);

    public void shutdown();
    public boolean isShutdown();

    @Override
    public void close(); // same as shutdown()
}

public interface ScheduledTask {
    boolean cancel(); // idempotent
    boolean isCancelled();
}
```

### Time & Precision Semantics

- Time source: `System.nanoTime()` (monotonic). A package-private constructor may accept a
  `LongSupplier nanoTimeSupplier` for tests.
- `tickDuration` is the scheduler granularity.
- Tasks are **never executed earlier than their deadline**.
- Tasks may be late by up to:
  - `< tickDuration` due to tick alignment
  - plus executor queuing/CPU scheduling delays

### Parameter Rules

- `tickDuration` must be positive.
- `wheelSize` must be > 0.
- `delay` and `initialDelay` must be >= 0.
- `period` / `fixedDelay` must be > 0 for periodic tasks.
- Durations that overflow nanoseconds conversion (`Duration.toNanos()` throwing
  `ArithmeticException`) should be rejected with `IllegalArgumentException`.

### Periodic Task Semantics

- Non-overlapping: the scheduler must not execute the same periodic task concurrently.
- If a periodic task throws, subsequent executions are suppressed (align with
  `ScheduledExecutorService` behavior).

`fixed-rate`:

- Maintain an internal `nextScheduledTimeNanos` (starts at `now + initialDelay`).
- After each run completes successfully: `nextScheduledTimeNanos += periodNanos`.
- Next delay is `max(0, nextScheduledTimeNanos - now)`. If behind, it will "catch up" by running
  again as soon as possible, but still serially (no overlap).

`fixed-delay`:

- After each run completes successfully: schedule next run at `completionTime + delayNanos`.

### Shutdown Semantics

- After `shutdown()`, new schedules are rejected (throw `RejectedExecutionException`).
- Already scheduled one-shot tasks may still be executed (best-effort).
- Periodic tasks stop rescheduling after shutdown.
- `cancel()` is always allowed and remains idempotent.

## Architecture Overview

We implement a **hierarchical timing wheel** where each level covers a time range (interval) and
has a fixed number of buckets. Tasks with deadlines beyond the current level's interval are placed
into an overflow level (with a coarser tick). As time advances, overflow buckets are flushed and
their tasks "cascade down" into lower levels until they become due.

Due buckets are managed by a single `DelayQueue`, which provides an efficient blocking mechanism
for the scheduler thread.

Key property for correctness:

- For a given level, tasks are only placed into that level if `expiration < currentTime + interval`.
  This guarantees that within one interval a bucket index maps to a unique expiration, so a bucket
  will never need to represent two different expirations concurrently.

## Main Data Structures

### `WheelLevel`

Represents one wheel level.

Fields:

- `long tickNanos`
- `int wheelSize`
- `long intervalNanos = tickNanos * wheelSize`
- `long currentTimeNanos` (always aligned to `tickNanos` boundary)
- `Bucket[] buckets`
- `WheelLevel overflow` (lazily created)

Methods (conceptual):

- `boolean add(TimerTaskEntry entry)`:
  - returns `true` if accepted into this level or overflow
  - returns `false` if the entry is already due (should execute now)
- `void advanceClock(long timeNanos)`:
  - advances `currentTimeNanos` if `timeNanos >= currentTimeNanos + tickNanos`
  - cascades to `overflow.advanceClock(currentTimeNanos)` if overflow exists

### `Bucket` (Delayed)

Represents one expiration tick at a particular wheel level.

Fields:

- `long expirationNanos` (initially `-1` meaning "not in DelayQueue")
- intrusive doubly-linked list of `TimerTaskEntry` (for O(1) cancel/remove)

Methods (conceptual):

- `boolean setExpiration(long expirationNanos)`:
  - sets expiration from `-1` to value and returns `true` if it changed (needs queue offer)
- `void flush(Consumer<TimerTaskEntry> reinsertOrExecute)`:
  - removes all entries from bucket
  - resets `expirationNanos` back to `-1`
  - invokes callback for each removed entry

`Bucket` implements `Delayed`:

- `getDelay(unit)` uses `expirationNanos - nanoTimeSupplier.getAsLong()`

### `TimerTaskEntry`

Represents one scheduled occurrence in the wheel.

Fields:

- `long expirationNanos` (aligned to tick)
- `Runnable runnable` (or a wrapper for periodic tasks)
- `TimerTaskEntry next/prev` (intrusive list links)
- `Bucket bucket` (current bucket pointer, null if not queued)
- `volatile boolean cancelled`

Cancel behavior:

- `cancel()` marks `cancelled=true` and, under the scheduler lock, removes itself from its current
  bucket list if present.
- Flush and execution must ignore cancelled entries.

## Scheduling & Execution Flow

### Aligning Deadlines

We align to tick boundaries to ensure "not earlier than deadline":

- `deadlineNanos = now + delayNanos`
- `expirationNanos = ceilToTick(deadlineNanos, tickNanos)`

If `expirationNanos < currentTimeNanos + tickNanos`, treat as due and execute immediately (by
submitting to the `Executor`).

### Adding an Entry

Algorithm (high-level):

1. Determine if the entry is due, or should be placed into the current `WheelLevel`, or overflow.
2. Compute:
   - `virtualId = expirationNanos / tickNanos`
   - `bucketIndex = virtualId % wheelSize`
3. Add entry to `buckets[bucketIndex]`.
4. If bucket's expiration was unset (`-1`) and now set to `bucketExpirationNanos`, offer bucket to
   the shared `DelayQueue`.

### Worker Thread Loop

- `take()` the next due `Bucket` from the `DelayQueue` (blocks).
- Under scheduler lock:
  1. `wheelLevel.advanceClock(bucket.expirationNanos)`
  2. `bucket.flush(entry -> wheel.addOrExecute(entry))`
- Outside the lock:
  - submit due tasks to the provided `Executor`

### Cascading Between Levels

When a bucket flushes:

- For each entry:
  - if cancelled: drop
  - else attempt `wheel.add(entry)` again (now that `currentTimeNanos` has advanced)
  - if it becomes due at the lowest level, it is executed

## Concurrency Model

- A single `ReentrantLock` protects:
  - wheel level current times
  - bucket lists
  - entry insert/remove
  - bucket expiration state transitions (`-1 <-> value`)
- Task execution is always outside the lock, via the provided `Executor`.
- `schedule(...)` and `cancel()` are thread-safe.
- If `Executor.execute()` throws `RejectedExecutionException`, treat it as a failed execution and:
  - call an internal `errorHandler` (defaults to `printStackTrace` or no-op, to be decided)
  - for periodic tasks: stop further rescheduling

## Testing Strategy

Tests must avoid `Thread.sleep` and flakiness:

- Provide a package-private constructor for `TimingWheelScheduler`:
  - injectable `LongSupplier nanoTimeSupplier` (manual clock)
  - optional `boolean autoStart` to avoid starting the worker thread during unit tests
- Provide a package-private `drain()` method for tests:
  - repeatedly `poll()` the `DelayQueue` and process all due buckets

Test executor: `Runnable::run` (direct executor) to keep ordering deterministic.

Coverage:

- one-shot delay executes no earlier than deadline
- cancellation prevents execution
- `fixed-rate`:
  - does not overlap
  - catches up with zero delay when behind (serially)
- `fixed-delay`:
  - schedules based on completion time
- shutdown rejects new schedules and stops periodic reschedule

## Open Questions (To Decide During Implementation)

- Public class/interface names: `TimingWheelScheduler` vs `HashedWheelScheduler` (the design uses a
  hierarchical wheel, not strictly "hashed wheel").
- Default error handling behavior (no-op vs stderr logging) and whether to expose a setter.

