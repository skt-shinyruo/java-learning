package yier.bubu.concurrency.ratelimit;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Fixed Window Counter:
 * Count requests in a fixed time window (e.g. 1 second). When the window rolls, the counter resets.
 * <p>
 * Pros: simplest implementation.
 * Cons: has boundary spikes (two adjacent windows can both be fully utilized).
 */
public final class FixedWindowCounterRateLimiter {
    private final int limit;
    private final long windowSizeMillis;
    private final LongSupplier timeMillisSupplier;

    private long windowStartMillis;
    private int countInWindow;

    public FixedWindowCounterRateLimiter(int limit, long windowSizeMillis) {
        this(limit, windowSizeMillis, System::currentTimeMillis);
    }

    FixedWindowCounterRateLimiter(int limit, long windowSizeMillis, LongSupplier timeMillisSupplier) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        if (windowSizeMillis <= 0) {
            throw new IllegalArgumentException("windowSizeMillis must be > 0");
        }
        this.limit = limit;
        this.windowSizeMillis = windowSizeMillis;
        this.timeMillisSupplier = Objects.requireNonNull(timeMillisSupplier, "timeMillisSupplier");

        long now = timeMillisSupplier.getAsLong();
        this.windowStartMillis = windowStart(now);
        this.countInWindow = 0;
    }

    public synchronized boolean tryAcquire() {
        return tryAcquire(1);
    }

    public synchronized boolean tryAcquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }

        long now = timeMillisSupplier.getAsLong();
        long start = windowStart(now);
        if (start != windowStartMillis) {
            windowStartMillis = start;
            countInWindow = 0;
        }

        if (countInWindow + permits > limit) {
            return false;
        }
        countInWindow += permits;
        return true;
    }

    public synchronized int getCountInWindow() {
        return countInWindow;
    }

    public synchronized long getWindowStartMillis() {
        return windowStartMillis;
    }

    private long windowStart(long nowMillis) {
        long windowId = Math.floorDiv(nowMillis, windowSizeMillis);
        return windowId * windowSizeMillis;
    }
}

