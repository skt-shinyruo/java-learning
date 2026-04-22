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
