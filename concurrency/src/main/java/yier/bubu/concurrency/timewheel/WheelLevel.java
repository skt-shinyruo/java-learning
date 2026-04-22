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
        int index = (int) Math.floorMod(virtualId, wheelSize);
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

