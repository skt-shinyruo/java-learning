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

