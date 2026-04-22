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

    public ScheduledTask scheduleWithFixedDelay(Runnable task, Duration initialDelay, Duration delay) {
        throw new UnsupportedOperationException("not implemented yet");
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
            boolean first = entry.cancel();
            if (first) {
                scheduler.removeFromWheel(entry);
            }
            return first;
        }

        @Override
        public boolean isCancelled() {
            return entry.isCancelled();
        }
    }
}
