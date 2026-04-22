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
            int index = (int) Math.floorMod(virtualId, wheelSize);
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
