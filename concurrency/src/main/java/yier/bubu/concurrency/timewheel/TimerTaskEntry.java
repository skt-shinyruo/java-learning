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

