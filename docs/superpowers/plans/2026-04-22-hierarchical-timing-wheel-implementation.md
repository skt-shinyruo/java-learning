# Hierarchical Timing Wheel Scheduler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a correct, readable, JDK-only hierarchical timing wheel scheduler in the `concurrency` module with deterministic JUnit 4 tests (no `Thread.sleep`).

**Architecture:** Build an in-memory hierarchical timing wheel backed by a shared `DelayQueue` of buckets (`TimerTaskList`). A scheduler owns one timing wheel, a lock for wheel mutations, and an `Executor` for running due tasks outside the lock. Periodic tasks are implemented as self-rescheduling wrappers with fixed-rate / fixed-delay semantics and "no overlap" execution.

**Tech Stack:** Java 8, Maven, JUnit 4, JDK `DelayQueue`, `ReentrantLock`, `Duration`

---

## File Structure

- Create: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/ScheduledTask.java`
  Responsibility: public cancellation handle for scheduled tasks.
- Create: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/Nanos.java`
  Responsibility: safe `Duration -> nanos` conversion + tick alignment helpers.
- Create: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimerTaskEntry.java`
  Responsibility: one scheduled occurrence stored in the wheel (intrusive list node + cancel flag).
- Create: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimerTaskList.java`
  Responsibility: one bucket (delayed) holding a linked list of entries; enqueued into `DelayQueue`.
- Create: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/WheelLevel.java`
  Responsibility: one wheel level (tick, interval, buckets) with optional overflow (added in Task 3).
- Create: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheel.java`
  Responsibility: root wheel wrapper (advance clock + add entry).
- Create: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheelScheduler.java`
  Responsibility: public scheduler API, locking, worker/drain logic, periodic wrappers, shutdown.
- Create: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/package-info.java`
  Responsibility: package-level documentation of semantics and important invariants.

- Create: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/ManualNanoClock.java`
  Responsibility: controllable nano-time source for deterministic tests.
- Create: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerOneShotTest.java`
  Responsibility: executable docs for one-shot scheduling + tick alignment.
- Create: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerCancelTest.java`
  Responsibility: cancellation semantics (idempotent, prevents execution).
- Create: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerOverflowTest.java`
  Responsibility: long-delay scheduling across wheel levels (hierarchical wheel).
- Create: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerFixedRateTest.java`
  Responsibility: fixed-rate periodic tasks (no overlap, catch-up behavior).
- Create: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerFixedDelayTest.java`
  Responsibility: fixed-delay periodic tasks (based on completion time).
- Create: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerShutdownTest.java`
  Responsibility: shutdown semantics (reject new schedules, stop periodic reschedule, keep one-shot).

## Preflight

- Do the work in an isolated git worktree (recommended):

```bash
git worktree add .worktrees/feat-timewheel -b feat-timewheel
cd .worktrees/feat-timewheel
```

- In restricted/sandboxed environments, `mvn test` may fail in the `jdk` module due to socket
  permissions. While implementing this feature, prefer running the `concurrency` module tests:

```bash
mvn -q -pl concurrency test
```

---

### Task 1: One-Shot Scheduling Slice (Single-Level Wheel + Manual Drain)

**Files:**
- Create: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/ManualNanoClock.java`
- Create: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerOneShotTest.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/ScheduledTask.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/Nanos.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimerTaskEntry.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimerTaskList.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/WheelLevel.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheel.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheelScheduler.java`

- [ ] **Step 1: Write the failing one-shot tests (tick alignment + not-before-deadline)**

```java
package yier.bubu.concurrency.timewheel;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TimingWheelSchedulerOneShotTest {
    @Test
    public void schedule_shouldRunAtExactTickWhenDelayAlignsToTick() {
        ManualNanoClock clock = new ManualNanoClock(0L);
        Executor direct = Runnable::run;

        TimingWheelScheduler scheduler =
                new TimingWheelScheduler(Duration.ofMillis(100), 20, direct, clock, false);

        AtomicInteger ran = new AtomicInteger(0);
        AtomicLong runAt = new AtomicLong(-1L);

        scheduler.schedule(() -> {
            ran.incrementAndGet();
            runAt.set(clock.nowNanos());
        }, Duration.ofMillis(200));

        clock.advance(Duration.ofMillis(199));
        scheduler.drain();
        Assert.assertEquals(0, ran.get());

        clock.advance(Duration.ofMillis(1));
        scheduler.drain();

        Assert.assertEquals(1, ran.get());
        Assert.assertEquals(TimeUnit.MILLISECONDS.toNanos(200), runAt.get());
    }

    @Test
    public void schedule_shouldNotRunBeforeDeadlineEvenWhenDelayIsNotTickAligned() {
        ManualNanoClock clock = new ManualNanoClock(0L);
        Executor direct = Runnable::run;

        TimingWheelScheduler scheduler =
                new TimingWheelScheduler(Duration.ofMillis(100), 20, direct, clock, false);

        AtomicInteger ran = new AtomicInteger(0);
        AtomicLong runAt = new AtomicLong(-1L);

        // deadline=150ms, but expiration must be ceil-to-tick => 200ms
        scheduler.schedule(() -> {
            ran.incrementAndGet();
            runAt.set(clock.nowNanos());
        }, Duration.ofMillis(150));

        clock.advance(Duration.ofMillis(199));
        scheduler.drain();
        Assert.assertEquals("not due yet", 0, ran.get());

        clock.advance(Duration.ofMillis(1));
        scheduler.drain();

        Assert.assertEquals(1, ran.get());
        Assert.assertEquals(TimeUnit.MILLISECONDS.toNanos(200), runAt.get());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails (missing classes)**

Run:

```bash
mvn -q -pl concurrency test -Dtest=TimingWheelSchedulerOneShotTest
```

Expected: FAIL with compilation errors (e.g., `cannot find symbol: class TimingWheelScheduler`).

- [ ] **Step 3: Add the minimal single-level wheel implementation to make the tests pass**

Create `concurrency/src/test/java/yier/bubu/concurrency/timewheel/ManualNanoClock.java`:

```java
package yier.bubu.concurrency.timewheel;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Manual, deterministic nano-time source for tests.
 */
final class ManualNanoClock implements LongSupplier {
    private final AtomicLong nowNanos;

    ManualNanoClock(long initialNanos) {
        this.nowNanos = new AtomicLong(initialNanos);
    }

    @Override
    public long getAsLong() {
        return nowNanos.get();
    }

    long nowNanos() {
        return nowNanos.get();
    }

    void setNanos(long nanos) {
        nowNanos.set(nanos);
    }

    void advance(Duration duration) {
        nowNanos.addAndGet(duration.toNanos());
    }
}
```

Create `concurrency/src/main/java/yier/bubu/concurrency/timewheel/ScheduledTask.java`:

```java
package yier.bubu.concurrency.timewheel;

/**
 * Cancellation handle for tasks scheduled by {@link TimingWheelScheduler}.
 */
public interface ScheduledTask {
    /**
     * @return true if this call transitioned the task into cancelled state; false if it was already cancelled
     */
    boolean cancel();

    boolean isCancelled();
}
```

Create `concurrency/src/main/java/yier/bubu/concurrency/timewheel/Nanos.java`:

```java
package yier.bubu.concurrency.timewheel;

import java.time.Duration;

/**
 * Time helpers for the timing wheel (JDK-only, Java 8 compatible).
 */
final class Nanos {
    private Nanos() {
    }

    static long positiveToNanos(Duration duration, String name) {
        long nanos = toNanos(duration, name);
        if (nanos <= 0) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return nanos;
    }

    static long nonNegativeToNanos(Duration duration, String name) {
        long nanos = toNanos(duration, name);
        if (nanos < 0) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
        return nanos;
    }

    private static long toNanos(Duration duration, String name) {
        if (duration == null) {
            throw new NullPointerException(name + " must not be null");
        }
        try {
            return duration.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(name + " is too large: " + duration, e);
        }
    }

    static long addExact(long a, long b, String message) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(message + ": a=" + a + ", b=" + b, e);
        }
    }

    static long multiplyExact(long a, long b, String message) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(message + ": a=" + a + ", b=" + b, e);
        }
    }

    static long alignDown(long timeNanos, long tickNanos) {
        return timeNanos - Math.floorMod(timeNanos, tickNanos);
    }

    /**
     * Align upwards to a tick boundary to guarantee "not before deadline".
     */
    static long ceilToTick(long timeNanos, long tickNanos) {
        long down = alignDown(timeNanos, tickNanos);
        if (down == timeNanos) {
            return timeNanos;
        }
        return down + tickNanos;
    }
}
```

Create `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimerTaskEntry.java`:

```java
package yier.bubu.concurrency.timewheel;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One scheduled occurrence stored in a bucket list.
 *
 * <p>Uses an intrusive linked list to support O(1) removals on cancel.
 */
final class TimerTaskEntry implements Runnable {
    private final Runnable task;
    private final long expirationNanos;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    TimerTaskList list;
    TimerTaskEntry prev;
    TimerTaskEntry next;

    TimerTaskEntry(Runnable task, long expirationNanos) {
        this.task = Objects.requireNonNull(task, "task");
        this.expirationNanos = expirationNanos;
    }

    long getExpirationNanos() {
        return expirationNanos;
    }

    boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void run() {
        task.run();
    }
}
```

Create `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimerTaskList.java`:

```java
package yier.bubu.concurrency.timewheel;

import java.util.Objects;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * One bucket (a list of entries) with a single expiration timestamp.
 *
 * <p>Expiration is set when the first entry is inserted for a given tick and reset to -1 after flush.
 */
final class TimerTaskList implements Delayed {
    private static final Runnable NOOP = () -> {
    };

    private final LongSupplier nanoTimeSupplier;
    private final TimerTaskEntry root;

    private long expirationNanos = -1L;

    TimerTaskList(LongSupplier nanoTimeSupplier) {
        this.nanoTimeSupplier = Objects.requireNonNull(nanoTimeSupplier, "nanoTimeSupplier");
        this.root = new TimerTaskEntry(NOOP, -1L);
        this.root.next = root;
        this.root.prev = root;
    }

    long getExpirationNanos() {
        return expirationNanos;
    }

    boolean setExpirationNanos(long expirationNanos) {
        if (this.expirationNanos == expirationNanos) {
            return false;
        }
        this.expirationNanos = expirationNanos;
        return true;
    }

    void add(TimerTaskEntry entry) {
        // Caller holds scheduler lock.
        if (entry.list != null) {
            entry.list.remove(entry);
        }

        entry.list = this;
        TimerTaskEntry tail = root.prev;
        entry.next = root;
        entry.prev = tail;
        tail.next = entry;
        root.prev = entry;
    }

    void remove(TimerTaskEntry entry) {
        // Caller holds scheduler lock.
        if (entry.list != this) {
            return;
        }
        entry.next.prev = entry.prev;
        entry.prev.next = entry.next;
        entry.next = null;
        entry.prev = null;
        entry.list = null;
    }

    void flush(Consumer<TimerTaskEntry> consumer) {
        // Caller holds scheduler lock.
        TimerTaskEntry cur = root.next;
        while (cur != root) {
            TimerTaskEntry next = cur.next;
            remove(cur);
            consumer.accept(cur);
            cur = next;
        }
        expirationNanos = -1L;
    }

    @Override
    public long getDelay(TimeUnit unit) {
        long delay = expirationNanos - nanoTimeSupplier.getAsLong();
        return unit.convert(delay, TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
        if (o == this) {
            return 0;
        }
        TimerTaskList other = (TimerTaskList) o;
        long diff = expirationNanos - other.expirationNanos;
        return diff < 0 ? -1 : (diff > 0 ? 1 : 0);
    }
}
```

Create `concurrency/src/main/java/yier/bubu/concurrency/timewheel/WheelLevel.java` (single-level only; overflow is added in Task 3):

```java
package yier.bubu.concurrency.timewheel;

import java.util.Objects;
import java.util.concurrent.DelayQueue;
import java.util.function.LongSupplier;

/**
 * One timing wheel level.
 *
 * <p>Task 1 implements only a single level. Overflow support is added later (Task 3).
 */
final class WheelLevel {
    private final long tickNanos;
    private final int wheelSize;
    private final long intervalNanos;

    private final TimerTaskList[] buckets;
    private final DelayQueue<TimerTaskList> delayQueue;

    private long currentTimeNanos; // aligned down to tick

    WheelLevel(long tickNanos,
               int wheelSize,
               long startTimeNanos,
               DelayQueue<TimerTaskList> delayQueue,
               LongSupplier nanoTimeSupplier) {
        this.tickNanos = tickNanos;
        this.wheelSize = wheelSize;
        this.intervalNanos = Nanos.multiplyExact(tickNanos, (long) wheelSize, "tickNanos * wheelSize overflows");
        this.delayQueue = Objects.requireNonNull(delayQueue, "delayQueue");

        this.currentTimeNanos = Nanos.alignDown(startTimeNanos, tickNanos);

        this.buckets = new TimerTaskList[wheelSize];
        for (int i = 0; i < wheelSize; i++) {
            buckets[i] = new TimerTaskList(nanoTimeSupplier);
        }
    }

    boolean add(TimerTaskEntry entry) {
        long expirationNanos = entry.getExpirationNanos();

        if (expirationNanos < currentTimeNanos + tickNanos) {
            // Due (or within the current tick window).
            return false;
        }

        if (expirationNanos >= currentTimeNanos + intervalNanos) {
            // Task 1 has no overflow wheel yet. We'll add hierarchical support in Task 3.
            throw new IllegalArgumentException("delay is out of range for single-level wheel: expirationNanos=" + expirationNanos);
        }

        long virtualId = expirationNanos / tickNanos;
        int index = (int) (virtualId % wheelSize);
        TimerTaskList bucket = buckets[index];

        bucket.add(entry);

        long bucketExpiration = virtualId * tickNanos;
        if (bucket.setExpirationNanos(bucketExpiration)) {
            delayQueue.offer(bucket);
        }
        return true;
    }

    void advanceClock(long timeNanos) {
        if (timeNanos >= currentTimeNanos + tickNanos) {
            currentTimeNanos = Nanos.alignDown(timeNanos, tickNanos);
        }
    }
}
```

Create `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheel.java`:

```java
package yier.bubu.concurrency.timewheel;

import java.util.Objects;
import java.util.concurrent.DelayQueue;
import java.util.function.LongSupplier;

final class TimingWheel {
    private final WheelLevel root;

    TimingWheel(long tickNanos,
                int wheelSize,
                long startTimeNanos,
                DelayQueue<TimerTaskList> delayQueue,
                LongSupplier nanoTimeSupplier) {
        Objects.requireNonNull(delayQueue, "delayQueue");
        Objects.requireNonNull(nanoTimeSupplier, "nanoTimeSupplier");
        this.root = new WheelLevel(tickNanos, wheelSize, startTimeNanos, delayQueue, nanoTimeSupplier);
    }

    boolean add(TimerTaskEntry entry) {
        return root.add(entry);
    }

    void advanceClock(long timeNanos) {
        root.advanceClock(timeNanos);
    }
}
```

Create `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheelScheduler.java` (periodic methods stubbed for now):

```java
package yier.bubu.concurrency.timewheel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * In-process timer scheduler based on a timing wheel.
 *
 * <p>For tests, a package-private constructor allows injecting a manual nano clock and disabling
 * the worker thread. Tests then call {@link #drain()} to deterministically process due buckets.
 */
public final class TimingWheelScheduler implements AutoCloseable {
    private final long tickNanos;
    private final Executor taskExecutor;
    private final LongSupplier nanoTimeSupplier;

    private final DelayQueue<TimerTaskList> delayQueue = new DelayQueue<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final TimingWheel timingWheel;

    private volatile boolean shutdown;

    public TimingWheelScheduler(Duration tickDuration, int wheelSize, Executor taskExecutor) {
        this(tickDuration, wheelSize, taskExecutor, System::nanoTime, true);
    }

    TimingWheelScheduler(Duration tickDuration,
                         int wheelSize,
                         Executor taskExecutor,
                         LongSupplier nanoTimeSupplier,
                         boolean autoStart) {
        this.tickNanos = Nanos.positiveToNanos(tickDuration, "tickDuration");
        if (wheelSize <= 0) {
            throw new IllegalArgumentException("wheelSize must be > 0");
        }
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
        this.nanoTimeSupplier = Objects.requireNonNull(nanoTimeSupplier, "nanoTimeSupplier");

        // Validate interval doesn't overflow.
        Nanos.multiplyExact(this.tickNanos, (long) wheelSize, "tickNanos * wheelSize overflows");

        long startTimeNanos = Nanos.alignDown(nanoTimeSupplier.getAsLong(), this.tickNanos);
        this.timingWheel = new TimingWheel(this.tickNanos, wheelSize, startTimeNanos, delayQueue, nanoTimeSupplier);

        if (autoStart) {
            startWorker();
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        shutdown = true;
    }

    public boolean isShutdown() {
        return shutdown;
    }

    public ScheduledTask schedule(Runnable task, Duration delay) {
        Objects.requireNonNull(task, "task");
        long delayNanos = Nanos.nonNegativeToNanos(delay, "delay");
        if (shutdown) {
            throw new RejectedExecutionException("scheduler is shutdown");
        }

        TimerTaskEntry entry = new TimerTaskEntry(task, computeExpiration(delayNanos));
        addOrExecute(entry);
        return new OneShotTask(this, entry);
    }

    public ScheduledTask scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public ScheduledTask scheduleWithFixedDelay(Runnable task, Duration initialDelay, Duration delay) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    void drain() {
        List<TimerTaskEntry> expiredEntries = new ArrayList<>();
        lock.lock();
        try {
            timingWheel.advanceClock(nanoTimeSupplier.getAsLong());

            TimerTaskList bucket;
            while ((bucket = delayQueue.poll()) != null) {
                timingWheel.advanceClock(bucket.getExpirationNanos());
                bucket.flush(entry -> {
                    if (entry.isCancelled()) {
                        return;
                    }
                    if (!timingWheel.add(entry)) {
                        expiredEntries.add(entry);
                    }
                });
            }
        } finally {
            lock.unlock();
        }

        for (TimerTaskEntry entry : expiredEntries) {
            submit(entry);
        }
    }

    private long computeExpiration(long delayNanos) {
        long now = nanoTimeSupplier.getAsLong();
        long deadline = Nanos.addExact(now, delayNanos, "now + delay overflows");
        return Nanos.ceilToTick(deadline, tickNanos);
    }

    private void addOrExecute(TimerTaskEntry entry) {
        boolean added;
        lock.lock();
        try {
            timingWheel.advanceClock(nanoTimeSupplier.getAsLong());
            added = timingWheel.add(entry);
        } finally {
            lock.unlock();
        }
        if (!added) {
            submit(entry);
        }
    }

    private void submit(TimerTaskEntry entry) {
        taskExecutor.execute(() -> {
            if (entry.isCancelled()) {
                return;
            }
            entry.run();
        });
    }

    private void startWorker() {
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    TimerTaskList bucket = delayQueue.take();
                    if (bucket != null) {
                        processBucket(bucket);
                    }
                } catch (InterruptedException ignored) {
                    // Best-effort daemon worker. Exit is handled by JVM shutdown.
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }, "timing-wheel-scheduler");
        thread.setDaemon(true);
        thread.start();
    }

    private void processBucket(TimerTaskList bucket) {
        List<TimerTaskEntry> expiredEntries = new ArrayList<>();
        lock.lock();
        try {
            timingWheel.advanceClock(bucket.getExpirationNanos());
            bucket.flush(entry -> {
                if (entry.isCancelled()) {
                    return;
                }
                if (!timingWheel.add(entry)) {
                    expiredEntries.add(entry);
                }
            });
        } finally {
            lock.unlock();
        }
        for (TimerTaskEntry entry : expiredEntries) {
            submit(entry);
        }
    }

    private static final class OneShotTask implements ScheduledTask {
        private final TimingWheelScheduler scheduler;
        private final TimerTaskEntry entry;

        private OneShotTask(TimingWheelScheduler scheduler, TimerTaskEntry entry) {
            this.scheduler = scheduler;
            this.entry = entry;
        }

        @Override
        public boolean cancel() {
            // Task 1: cancellation is best-effort (removal semantics are added in Task 2).
            return entry.cancel();
        }

        @Override
        public boolean isCancelled() {
            return entry.isCancelled();
        }
    }
}
```

- [ ] **Step 4: Run the one-shot tests again to verify they pass**

Run:

```bash
mvn -q -pl concurrency test -Dtest=TimingWheelSchedulerOneShotTest
```

Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```bash
git add \
  concurrency/src/main/java/yier/bubu/concurrency/timewheel \
  concurrency/src/test/java/yier/bubu/concurrency/timewheel/ManualNanoClock.java \
  concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerOneShotTest.java
git commit -m "feat(concurrency): add timing wheel one-shot scheduler slice"
```

---

### Task 2: Add Cancellation Semantics (Idempotent + Removes From Wheel)

**Files:**
- Create: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerCancelTest.java`
- Modify: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheelScheduler.java`

- [ ] **Step 1: Write the failing cancellation test**

```java
package yier.bubu.concurrency.timewheel;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TimingWheelSchedulerCancelTest {
    @Test
    public void cancel_shouldBeIdempotentAndPreventExecution() {
        ManualNanoClock clock = new ManualNanoClock(0L);
        Executor direct = Runnable::run;

        TimingWheelScheduler scheduler =
                new TimingWheelScheduler(Duration.ofMillis(100), 20, direct, clock, false);

        AtomicInteger ran = new AtomicInteger(0);
        ScheduledTask handle = scheduler.schedule(ran::incrementAndGet, Duration.ofMillis(200));

        Assert.assertTrue(handle.cancel());
        Assert.assertFalse("second cancel is idempotent", handle.cancel());

        clock.advance(Duration.ofMillis(200));
        scheduler.drain();
        Assert.assertEquals(0, ran.get());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
mvn -q -pl concurrency test -Dtest=TimingWheelSchedulerCancelTest
```

Expected: FAIL because Task 1 only marks cancelled but may still execute if already flushed/submitted.

- [ ] **Step 3: Implement cancel removal under the scheduler lock**

Modify `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheelScheduler.java`:

1) Add a helper:

```java
    private void removeFromWheel(TimerTaskEntry entry) {
        lock.lock();
        try {
            if (entry.list != null) {
                entry.list.remove(entry);
            }
        } finally {
            lock.unlock();
        }
    }
```

2) Update `OneShotTask.cancel()` to remove:

```java
        @Override
        public boolean cancel() {
            boolean first = entry.cancel();
            if (first) {
                scheduler.removeFromWheel(entry);
            }
            return first;
        }
```

3) Strengthen `submit(...)` to re-check cancellation inside the executor task:

```java
    private void submit(TimerTaskEntry entry) {
        taskExecutor.execute(() -> {
            if (entry.isCancelled()) {
                return;
            }
            entry.run();
        });
    }
```

- [ ] **Step 4: Run cancellation + one-shot tests**

Run:

```bash
mvn -q -pl concurrency test -Dtest=TimingWheelSchedulerCancelTest,TimingWheelSchedulerOneShotTest
```

Expected: PASS.

- [ ] **Step 5: Commit Task 2**

```bash
git add \
  concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheelScheduler.java \
  concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerCancelTest.java
git commit -m "feat(concurrency): support cancellation for timing wheel tasks"
```

---

### Task 3: Add Hierarchical Overflow Wheel (Long Delays)

**Files:**
- Create: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerOverflowTest.java`
- Modify: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/WheelLevel.java`

- [ ] **Step 1: Write the failing overflow test (delay beyond base interval)**

```java
package yier.bubu.concurrency.timewheel;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TimingWheelSchedulerOverflowTest {
    @Test
    public void longDelay_shouldBeHandledByOverflowWheelAndStillFireAtDeadlineTick() {
        ManualNanoClock clock = new ManualNanoClock(0L);
        Executor direct = Runnable::run;

        // tick=100ms, wheelSize=10 => base interval=1000ms
        TimingWheelScheduler scheduler =
                new TimingWheelScheduler(Duration.ofMillis(100), 10, direct, clock, false);

        AtomicInteger ran = new AtomicInteger(0);
        AtomicLong runAt = new AtomicLong(-1L);

        scheduler.schedule(() -> {
            ran.incrementAndGet();
            runAt.set(clock.nowNanos());
        }, Duration.ofMillis(2_500));

        clock.advance(Duration.ofMillis(2_499));
        scheduler.drain();
        Assert.assertEquals(0, ran.get());

        clock.advance(Duration.ofMillis(1));
        scheduler.drain();
        Assert.assertEquals(1, ran.get());
        Assert.assertEquals(TimeUnit.MILLISECONDS.toNanos(2_500), runAt.get());
    }
}
```

- [ ] **Step 2: Run the overflow test to see the failure**

Run:

```bash
mvn -q -pl concurrency test -Dtest=TimingWheelSchedulerOverflowTest
```

Expected: FAIL with `IllegalArgumentException: delay is out of range for single-level wheel`.

- [ ] **Step 3: Implement overflow support in `WheelLevel`**

Modify `concurrency/src/main/java/yier/bubu/concurrency/timewheel/WheelLevel.java` to add an
overflow wheel whose tick is this level's interval.

Replace the class with:

```java
package yier.bubu.concurrency.timewheel;

import java.util.Objects;
import java.util.concurrent.DelayQueue;
import java.util.function.LongSupplier;

/**
 * One timing wheel level. Tasks beyond this level's interval are stored in an overflow level whose
 * tick is {@code intervalNanos}.
 */
final class WheelLevel {
    private final long tickNanos;
    private final int wheelSize;
    private final long intervalNanos;

    private final TimerTaskList[] buckets;
    private final DelayQueue<TimerTaskList> delayQueue;
    private final LongSupplier nanoTimeSupplier;

    private long currentTimeNanos; // aligned down to tick
    private WheelLevel overflow;

    WheelLevel(long tickNanos,
               int wheelSize,
               long startTimeNanos,
               DelayQueue<TimerTaskList> delayQueue,
               LongSupplier nanoTimeSupplier) {
        this.tickNanos = tickNanos;
        this.wheelSize = wheelSize;
        this.intervalNanos = Nanos.multiplyExact(tickNanos, (long) wheelSize, "tickNanos * wheelSize overflows");
        this.delayQueue = Objects.requireNonNull(delayQueue, "delayQueue");
        this.nanoTimeSupplier = Objects.requireNonNull(nanoTimeSupplier, "nanoTimeSupplier");

        this.currentTimeNanos = Nanos.alignDown(startTimeNanos, tickNanos);

        this.buckets = new TimerTaskList[wheelSize];
        for (int i = 0; i < wheelSize; i++) {
            buckets[i] = new TimerTaskList(nanoTimeSupplier);
        }
    }

    boolean add(TimerTaskEntry entry) {
        long expirationNanos = entry.getExpirationNanos();

        if (expirationNanos < currentTimeNanos + tickNanos) {
            return false;
        }

        if (expirationNanos < currentTimeNanos + intervalNanos) {
            long virtualId = expirationNanos / tickNanos;
            int index = (int) (virtualId % wheelSize);
            TimerTaskList bucket = buckets[index];

            bucket.add(entry);

            long bucketExpiration = virtualId * tickNanos;
            if (bucket.setExpirationNanos(bucketExpiration)) {
                delayQueue.offer(bucket);
            }
            return true;
        }

        // Overflow: coarser tick = this interval.
        if (overflow == null) {
            overflow = new WheelLevel(intervalNanos, wheelSize, currentTimeNanos, delayQueue, nanoTimeSupplier);
        }
        return overflow.add(entry);
    }

    void advanceClock(long timeNanos) {
        if (timeNanos >= currentTimeNanos + tickNanos) {
            currentTimeNanos = Nanos.alignDown(timeNanos, tickNanos);
            if (overflow != null) {
                overflow.advanceClock(currentTimeNanos);
            }
        }
    }
}
```

- [ ] **Step 4: Run overflow + previous tests**

Run:

```bash
mvn -q -pl concurrency test -Dtest=TimingWheelSchedulerOverflowTest,TimingWheelSchedulerCancelTest,TimingWheelSchedulerOneShotTest
```

Expected: PASS.

- [ ] **Step 5: Commit Task 3**

```bash
git add \
  concurrency/src/main/java/yier/bubu/concurrency/timewheel/WheelLevel.java \
  concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerOverflowTest.java
git commit -m "feat(concurrency): add hierarchical overflow wheel support"
```

---

### Task 4: Fixed-Rate Periodic Tasks (No Overlap)

**Files:**
- Create: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerFixedRateTest.java`
- Modify: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheelScheduler.java`

- [ ] **Step 1: Write the failing fixed-rate tests**

```java
package yier.bubu.concurrency.timewheel;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

public class TimingWheelSchedulerFixedRateTest {
    private static final class RecordingExecutor implements Executor {
        private final Deque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queue.addLast(command);
        }

        int size() {
            return queue.size();
        }

        void runNext() {
            queue.removeFirst().run();
        }
    }

    @Test
    public void fixedRate_shouldNotOverlapAndShouldCatchUpWithZeroDelay() {
        ManualNanoClock clock = new ManualNanoClock(0L);
        RecordingExecutor executor = new RecordingExecutor();

        TimingWheelScheduler scheduler =
                new TimingWheelScheduler(Duration.ofMillis(100), 20, executor, clock, false);

        AtomicInteger ran = new AtomicInteger(0);
        ScheduledTask handle =
                scheduler.scheduleAtFixedRate(ran::incrementAndGet, Duration.ZERO, Duration.ofMillis(100));

        // initialDelay=0 -> first run is due, so it should be submitted once
        Assert.assertEquals(1, executor.size());

        // advance time but DO NOT execute the queued runnable => no overlap => no more submissions
        clock.advance(Duration.ofMillis(1_000));
        scheduler.drain();
        Assert.assertEquals(1, executor.size());

        // execute once => should reschedule again (we're behind => delay=0 => immediate submit)
        executor.runNext();
        Assert.assertEquals(1, ran.get());
        Assert.assertEquals(1, executor.size());

        // cancel should prevent future runs
        Assert.assertTrue(handle.cancel());
        executor.runNext(); // should be a no-op due to cancellation
        Assert.assertEquals(1, ran.get());
        Assert.assertEquals(0, executor.size());
    }
}
```

- [ ] **Step 2: Run the fixed-rate test to see the failure**

Run:

```bash
mvn -q -pl concurrency test -Dtest=TimingWheelSchedulerFixedRateTest
```

Expected: FAIL (currently `scheduleAtFixedRate` throws `UnsupportedOperationException`).

- [ ] **Step 3: Implement fixed-rate periodic scheduling**

Modify `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheelScheduler.java`:

1) Add `scheduleAtFixedRate(...)` and a `PeriodicTask` implementation.

Use this implementation (insert into the class; keep existing methods):

```java
    public ScheduledTask scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period) {
        Objects.requireNonNull(task, "task");
        long initialDelayNanos = Nanos.nonNegativeToNanos(initialDelay, "initialDelay");
        long periodNanos = Nanos.positiveToNanos(period, "period");
        if (shutdown) {
            throw new RejectedExecutionException("scheduler is shutdown");
        }

        PeriodicTask periodic = new PeriodicTask(task, initialDelayNanos, periodNanos, 0L, PeriodicMode.FIXED_RATE);
        periodic.scheduleFirst();
        return periodic;
    }

    private enum PeriodicMode {
        FIXED_RATE,
        FIXED_DELAY
    }

    private final class PeriodicTask implements ScheduledTask, Runnable {
        private final Runnable userTask;
        private final long initialDelayNanos;
        private final long periodNanos;
        private final long fixedDelayNanos;
        private final PeriodicMode mode;

        private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

        // For FIXED_RATE: the next target time (in nanos), updated after each run. Guarded by "this".
        private long nextScheduledTimeNanos;

        // The currently scheduled entry (if any). Used for best-effort cancellation of pending wheel entry.
        private volatile TimerTaskEntry currentEntry;

        private PeriodicTask(Runnable userTask,
                             long initialDelayNanos,
                             long periodNanos,
                             long fixedDelayNanos,
                             PeriodicMode mode) {
            this.userTask = userTask;
            this.initialDelayNanos = initialDelayNanos;
            this.periodNanos = periodNanos;
            this.fixedDelayNanos = fixedDelayNanos;
            this.mode = mode;
        }

        private void scheduleFirst() {
            long now = nanoTimeSupplier.getAsLong();
            synchronized (this) {
                this.nextScheduledTimeNanos = Nanos.addExact(now, initialDelayNanos, "now + initialDelay overflows");
            }
            this.currentEntry = scheduleInternal(this, initialDelayNanos);
        }

        @Override
        public void run() {
            if (cancelled.get()) {
                return;
            }
            if (shutdown) {
                // After shutdown, periodic tasks must not reschedule.
                return;
            }

            try {
                userTask.run();
            } catch (Throwable t) {
                // Align with ScheduledExecutorService: suppress subsequent executions.
                cancelled.set(true);
                t.printStackTrace();
                return;
            }

            if (cancelled.get() || shutdown) {
                return;
            }

            long now = nanoTimeSupplier.getAsLong();
            long nextDelayNanos;
            if (mode == PeriodicMode.FIXED_RATE) {
                synchronized (this) {
                    nextScheduledTimeNanos = Nanos.addExact(nextScheduledTimeNanos, periodNanos, "nextScheduledTime + period overflows");
                    nextDelayNanos = Math.max(0L, nextScheduledTimeNanos - now);
                }
            } else {
                nextDelayNanos = fixedDelayNanos;
            }

            currentEntry = scheduleInternal(this, nextDelayNanos);
        }

        @Override
        public boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return false;
            }
            TimerTaskEntry entry = currentEntry;
            if (entry != null) {
                // Mark cancelled and best-effort remove from wheel if still queued.
                entry.cancel();
                removeFromWheel(entry);
            }
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    private TimerTaskEntry scheduleInternal(Runnable task, long delayNanos) {
        TimerTaskEntry entry = new TimerTaskEntry(task, computeExpiration(delayNanos));
        addOrExecute(entry);
        return entry;
    }
```

2) Ensure `removeFromWheel(...)` exists from Task 2. If it doesn't, add it now.

- [ ] **Step 4: Run fixed-rate + previous tests**

Run:

```bash
mvn -q -pl concurrency test -Dtest=TimingWheelSchedulerFixedRateTest,TimingWheelSchedulerOverflowTest,TimingWheelSchedulerCancelTest,TimingWheelSchedulerOneShotTest
```

Expected: PASS.

- [ ] **Step 5: Commit Task 4**

```bash
git add \
  concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheelScheduler.java \
  concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerFixedRateTest.java
git commit -m "feat(concurrency): add fixed-rate periodic scheduling"
```

---

### Task 5: Fixed-Delay Periodic Tasks (Based on Completion Time)

**Files:**
- Create: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerFixedDelayTest.java`
- Modify: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheelScheduler.java`

- [ ] **Step 1: Write the failing fixed-delay test**

```java
package yier.bubu.concurrency.timewheel;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TimingWheelSchedulerFixedDelayTest {
    private static final class RecordingExecutor implements Executor {
        private final Deque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queue.addLast(command);
        }

        int size() {
            return queue.size();
        }

        void runNext() {
            queue.removeFirst().run();
        }
    }

    @Test
    public void fixedDelay_shouldScheduleNextRunFromCompletionTime() {
        ManualNanoClock clock = new ManualNanoClock(0L);
        RecordingExecutor executor = new RecordingExecutor();

        // Use smaller tick to avoid rounding noise in this test.
        TimingWheelScheduler scheduler =
                new TimingWheelScheduler(Duration.ofMillis(10), 20, executor, clock, false);

        AtomicInteger ran = new AtomicInteger(0);

        scheduler.scheduleWithFixedDelay(() -> {
            ran.incrementAndGet();
            // simulate work: completion time moves forward by 50ms
            clock.advance(Duration.ofMillis(50));
        }, Duration.ZERO, Duration.ofMillis(100));

        Assert.assertEquals(1, executor.size());
        executor.runNext();
        Assert.assertEquals(1, ran.get());

        // If fixed-delay uses completion time (50ms), next should be at 150ms.
        clock.setNanos(TimeUnit.MILLISECONDS.toNanos(100));
        scheduler.drain();
        Assert.assertEquals("should not run at 100ms", 0, executor.size());

        clock.setNanos(TimeUnit.MILLISECONDS.toNanos(149));
        scheduler.drain();
        Assert.assertEquals(0, executor.size());

        clock.setNanos(TimeUnit.MILLISECONDS.toNanos(150));
        scheduler.drain();
        Assert.assertEquals(1, executor.size());
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run:

```bash
mvn -q -pl concurrency test -Dtest=TimingWheelSchedulerFixedDelayTest
```

Expected: FAIL because `scheduleWithFixedDelay` is still `UnsupportedOperationException`.

- [ ] **Step 3: Implement `scheduleWithFixedDelay(...)` by reusing `PeriodicTask`**

Modify `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheelScheduler.java`:

Replace `scheduleWithFixedDelay(...)` stub with:

```java
    public ScheduledTask scheduleWithFixedDelay(Runnable task, Duration initialDelay, Duration delay) {
        Objects.requireNonNull(task, "task");
        long initialDelayNanos = Nanos.nonNegativeToNanos(initialDelay, "initialDelay");
        long fixedDelayNanos = Nanos.positiveToNanos(delay, "delay");
        if (shutdown) {
            throw new RejectedExecutionException("scheduler is shutdown");
        }

        PeriodicTask periodic = new PeriodicTask(task, initialDelayNanos, 0L, fixedDelayNanos, PeriodicMode.FIXED_DELAY);
        periodic.scheduleFirst();
        return periodic;
    }
```

- [ ] **Step 4: Run fixed-delay + full timewheel test set**

Run:

```bash
mvn -q -pl concurrency test -Dtest=TimingWheelSchedulerFixedDelayTest,TimingWheelSchedulerFixedRateTest,TimingWheelSchedulerOverflowTest,TimingWheelSchedulerCancelTest,TimingWheelSchedulerOneShotTest
```

Expected: PASS.

- [ ] **Step 5: Commit Task 5**

```bash
git add \
  concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheelScheduler.java \
  concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerFixedDelayTest.java
git commit -m "feat(concurrency): add fixed-delay periodic scheduling"
```

---

### Task 6: Shutdown Semantics

**Files:**
- Create: `concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerShutdownTest.java`
- Modify: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheelScheduler.java`

- [ ] **Step 1: Write the failing shutdown tests**

```java
package yier.bubu.concurrency.timewheel;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TimingWheelSchedulerShutdownTest {
    private static final class RecordingExecutor implements Executor {
        private final Deque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queue.addLast(command);
        }

        int size() {
            return queue.size();
        }

        void runNext() {
            queue.removeFirst().run();
        }
    }

    @Test
    public void shutdown_shouldRejectNewSchedules() {
        ManualNanoClock clock = new ManualNanoClock(0L);
        Executor direct = Runnable::run;
        TimingWheelScheduler scheduler =
                new TimingWheelScheduler(Duration.ofMillis(100), 20, direct, clock, false);

        scheduler.shutdown();
        try {
            scheduler.schedule(() -> {
            }, Duration.ZERO);
            Assert.fail("expected RejectedExecutionException");
        } catch (RejectedExecutionException expected) {
        }
    }

    @Test
    public void shutdown_shouldNotPreventExistingOneShotFromRunning() {
        ManualNanoClock clock = new ManualNanoClock(0L);
        Executor direct = Runnable::run;
        TimingWheelScheduler scheduler =
                new TimingWheelScheduler(Duration.ofMillis(100), 20, direct, clock, false);

        AtomicInteger ran = new AtomicInteger(0);
        scheduler.schedule(ran::incrementAndGet, Duration.ofMillis(200));

        scheduler.shutdown();

        clock.advance(Duration.ofMillis(200));
        scheduler.drain();
        Assert.assertEquals(1, ran.get());
    }

    @Test
    public void shutdown_shouldStopPeriodicReschedule() {
        ManualNanoClock clock = new ManualNanoClock(0L);
        RecordingExecutor executor = new RecordingExecutor();
        TimingWheelScheduler scheduler =
                new TimingWheelScheduler(Duration.ofMillis(100), 20, executor, clock, false);

        AtomicInteger ran = new AtomicInteger(0);
        scheduler.scheduleAtFixedRate(ran::incrementAndGet, Duration.ZERO, Duration.ofMillis(100));

        Assert.assertEquals(1, executor.size());

        // Shut down before the first run executes.
        scheduler.shutdown();
        executor.runNext();

        Assert.assertEquals(1, ran.get());
        Assert.assertEquals("no reschedule after shutdown", 0, executor.size());
    }
}
```

- [ ] **Step 2: Run the shutdown test to see it fail**

Run:

```bash
mvn -q -pl concurrency test -Dtest=TimingWheelSchedulerShutdownTest
```

Expected: FAIL because periodic tasks may still reschedule after shutdown without checking.

- [ ] **Step 3: Ensure periodic tasks stop rescheduling after shutdown**

Modify `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheelScheduler.java`:

- In `PeriodicTask.run()`, keep the existing guard:

```java
            if (shutdown) {
                // After shutdown, periodic tasks must not reschedule.
                return;
            }
```

- And add a second guard right before `scheduleInternal(...)`:

```java
            if (cancelled.get() || shutdown) {
                return;
            }
```

This ensures shutdown between "user task done" and "reschedule" stops future runs.

- [ ] **Step 4: Run shutdown + full timewheel test set**

Run:

```bash
mvn -q -pl concurrency test -Dtest=TimingWheelSchedulerShutdownTest,TimingWheelSchedulerFixedDelayTest,TimingWheelSchedulerFixedRateTest,TimingWheelSchedulerOverflowTest,TimingWheelSchedulerCancelTest,TimingWheelSchedulerOneShotTest
```

Expected: PASS.

- [ ] **Step 5: Commit Task 6**

```bash
git add \
  concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheelScheduler.java \
  concurrency/src/test/java/yier/bubu/concurrency/timewheel/TimingWheelSchedulerShutdownTest.java
git commit -m "feat(concurrency): define shutdown behavior for timing wheel scheduler"
```

---

### Task 7: Documentation Polish + Final Regression

**Files:**
- Create: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/package-info.java`
- Modify: `concurrency/src/main/java/yier/bubu/concurrency/timewheel/TimingWheelScheduler.java`

- [ ] **Step 1: Add package-level documentation**

Create `concurrency/src/main/java/yier/bubu/concurrency/timewheel/package-info.java`:

```java
/**
 * Timing wheel scheduler (hierarchical).
 *
 * <p>Key semantics:
 * <ul>
 *   <li>Time source is monotonic nano time ({@code System.nanoTime()}).</li>
 *   <li>Tick alignment uses ceil-to-tick, so tasks are never executed earlier than their deadline.</li>
 *   <li>Periodic tasks are non-overlapping: the next run is scheduled only after the previous run completes.</li>
 * </ul>
 *
 * <p>Internal design:
 * <ul>
 *   <li>Each wheel level has {@code wheelSize} buckets and covers {@code interval=tick*wheelSize}.</li>
 *   <li>Tasks beyond the current level interval go to an overflow level with tick=interval.</li>
 *   <li>All buckets share one {@code DelayQueue} to let the worker block until the next bucket expires.</li>
 * </ul>
 */
package yier.bubu.concurrency.timewheel;
```

- [ ] **Step 2: Add a small Chinese comment in `WheelLevel.add(...)` about the overflow invariant**

Modify `WheelLevel.add(...)` (comment only):

```java
        // 关键不变量：只有 expiration < currentTime + interval 的任务才进入本层，
        // 否则就交给 overflow（更粗粒度的 tick）存储，等时间推进后再下沉到更低层。
```

- [ ] **Step 3: Run full `concurrency` module tests**

Run:

```bash
mvn -q -pl concurrency test
```

Expected: PASS.

- [ ] **Step 4: Commit Task 7**

```bash
git add \
  concurrency/src/main/java/yier/bubu/concurrency/timewheel/package-info.java \
  concurrency/src/main/java/yier/bubu/concurrency/timewheel/WheelLevel.java
git commit -m "docs(concurrency): add timing wheel package documentation"
```

