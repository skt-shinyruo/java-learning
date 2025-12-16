package yier.bubu.concurrency.ratelimit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Sliding Window Log (Sliding Log):
 * Store the timestamp of each accepted request; reject when the number of timestamps in the last window exceeds the limit.
 * <p>
 * Pros: most accurate in strict sliding-window sense.
 * Cons: high memory/CPU at high QPS (needs to keep many timestamps).
 */
public final class SlidingLogRateLimiter {
    private final int limit;
    private final long windowSizeMillis;
    private final LongSupplier timeMillisSupplier;
    private final Deque<Long> acceptedTimestampsMillis;

    public SlidingLogRateLimiter(int limit, long windowSizeMillis) {
        this(limit, windowSizeMillis, System::currentTimeMillis);
    }

    SlidingLogRateLimiter(int limit, long windowSizeMillis, LongSupplier timeMillisSupplier) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        if (windowSizeMillis <= 0) {
            throw new IllegalArgumentException("windowSizeMillis must be > 0");
        }
        this.limit = limit;
        this.windowSizeMillis = windowSizeMillis;
        this.timeMillisSupplier = Objects.requireNonNull(timeMillisSupplier, "timeMillisSupplier");
        this.acceptedTimestampsMillis = new ArrayDeque<>(Math.min(limit, 1024));
    }

    public synchronized boolean tryAcquire() {
        return tryAcquire(1);
    }

    public synchronized boolean tryAcquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }
        if (permits > limit) {
            return false;
        }

        long nowMillis = timeMillisSupplier.getAsLong();
        long windowStartMillis = nowMillis - windowSizeMillis;
        while (!acceptedTimestampsMillis.isEmpty()) {
            long first = acceptedTimestampsMillis.peekFirst();
            if (first > windowStartMillis) {
                break;
            }
            acceptedTimestampsMillis.removeFirst();
        }

        if (acceptedTimestampsMillis.size() + permits > limit) {
            return false;
        }
        for (int i = 0; i < permits; i++) {
            acceptedTimestampsMillis.addLast(nowMillis);
        }
        return true;
    }

    public synchronized int getCurrentSize() {
        long nowMillis = timeMillisSupplier.getAsLong();
        long windowStartMillis = nowMillis - windowSizeMillis;
        while (!acceptedTimestampsMillis.isEmpty()) {
            long first = acceptedTimestampsMillis.peekFirst();
            if (first > windowStartMillis) {
                break;
            }
            acceptedTimestampsMillis.removeFirst();
        }
        return acceptedTimestampsMillis.size();
    }
}

