# Simple Timing Wheel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the advanced hierarchical timewheel scheduler with a single-file teaching implementation.

**Architecture:** Keep `yier.bubu.concurrency.timewheel` focused on one class, `SimpleTimingWheel`, that demonstrates single-level slots, tick progression, remaining rounds, and lock-protected bucket mutation. Remove advanced scheduler APIs, overflow levels, `DelayQueue`, cancellation handles, periodic scheduling, and the old tests that assert those behaviors.

**Tech Stack:** Java 8, JUnit 4, Maven Surefire, MkDocs Markdown.

## Global Constraints

- Java source must remain Java 8-compatible.
- Production code lives under `concurrency/src/main/java/yier/bubu/concurrency/timewheel`.
- Tests live under `concurrency/src/test/java/yier/bubu/concurrency/timewheel`.
- Documentation source lives under `concurrency/docs`; do not edit generated `mkdocs/site`.
- Keep the teaching implementation in one public Java file.

---

### Task 1: Simple Timing Wheel Tests

**Files:**
- Create: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/SimpleTimingWheelTest.java`

**Interfaces:**
- Consumes: planned class `SimpleTimingWheel`
- Produces: assertions for `ticksForDelay(long, long)`, `position(int, int, long)`, constructor validation, lifecycle validation, and one smoke execution path

- [ ] **Step 1: Write failing tests**

Create `SimpleTimingWheelTest` with tests for ceil-to-tick conversion, target slot and remaining rounds, invalid arguments, lifecycle errors, and a short smoke execution using `CountDownLatch`.

- [ ] **Step 2: Verify RED**

Run: `mvn -pl concurrency -Dtest=SimpleTimingWheelTest test`

Expected: compilation fails because `SimpleTimingWheel` does not exist.

### Task 2: Replace Advanced Implementation

**Files:**
- Delete: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheelScheduler.java`
- Delete: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheel.java`
- Delete: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/WheelLevel.java`
- Delete: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimerTaskList.java`
- Delete: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimerTaskEntry.java`
- Delete: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/ScheduledTask.java`
- Delete: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/Nanos.java`
- Modify: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/package-info.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/SimpleTimingWheel.java`

**Interfaces:**
- Consumes: tests from Task 1
- Produces: `public final class SimpleTimingWheel implements AutoCloseable`
- Produces: `public SimpleTimingWheel(long tickMillis, int wheelSize)`
- Produces: `public void start()`
- Produces: `public void schedule(Runnable task, long delayMillis)`
- Produces: `public void stop()`
- Produces: `public void close()`
- Produces: package-private `static long ticksForDelay(long delayMillis, long tickMillis)`
- Produces: package-private `static TimeoutPosition position(int currentSlot, int wheelSize, long ticks)`

- [ ] **Step 1: Implement minimal class**

Use `ReentrantLock` for bucket mutation. Use `LockSupport.parkNanos` in the worker loop and `LockSupport.unpark` in `stop()`. Execute due tasks outside the lock and catch `Throwable` so the worker continues.

- [ ] **Step 2: Verify GREEN**

Run: `mvn -pl concurrency -Dtest=SimpleTimingWheelTest test`

Expected: `SimpleTimingWheelTest` passes.

### Task 3: Remove Advanced Tests

**Files:**
- Delete: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerOneShotTest.java`
- Delete: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerCancelTest.java`
- Delete: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerFixedRateTest.java`
- Delete: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerFixedDelayTest.java`
- Delete: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerOverflowTest.java`
- Delete: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerShutdownTest.java`
- Delete: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/ManualNanoClock.java`

**Interfaces:**
- Consumes: `SimpleTimingWheelTest`
- Produces: no tests that reference deleted advanced APIs

- [ ] **Step 1: Delete obsolete tests**

Remove tests for cancellation, fixed-rate, fixed-delay, overflow, shutdown, and manual nano clock injection because those concepts are intentionally not in the teaching version.

- [ ] **Step 2: Run module tests**

Run: `mvn -pl concurrency test`

Expected: module tests pass with only `SimpleTimingWheelTest` covering the timewheel package.

### Task 4: Rewrite Documentation

**Files:**
- Create: `concurrency/docs/simple-timing-wheel.md`
- Modify: `mkdocs/mkdocs.yml`

**Interfaces:**
- Consumes: `SimpleTimingWheel`
- Produces: documentation that describes the single-level teaching implementation and no longer claims advanced scheduler semantics

- [ ] **Step 1: Rewrite the document**

Describe `tickMillis`, `wheelSize`, slots, `remainingRounds`, `ReentrantLock`, `LockSupport.parkNanos`, lifecycle, and known limitations.

- [ ] **Step 2: Build docs**

Run: `mkdocs build -f mkdocs/mkdocs.yml`

Expected: MkDocs build exits 0.

### Task 5: Final Verification

**Files:**
- Check all changed files

**Interfaces:**
- Consumes: all previous tasks
- Produces: verified working tree summary

- [ ] **Step 1: Run full verification**

Run:

```bash
mvn -pl concurrency test
mkdocs build -f mkdocs/mkdocs.yml
git status --short
```

Expected: tests and docs build pass; status shows only intended implementation, test, doc, and plan changes.
