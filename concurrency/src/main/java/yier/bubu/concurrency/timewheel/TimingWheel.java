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

