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
        // 移除窗口外的历史记录：队头永远是最早的时间戳，因此可以一直从前往后清理。
        // 该步骤的时间复杂度与“过期的数量”成正比。
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
        // permits 视为同一时刻到达的多个请求：记录相同的时间戳即可。
        for (int i = 0; i < permits; i++) {
            acceptedTimestampsMillis.addLast(nowMillis);
        }
        return true;
    }

    public synchronized int getCurrentSize() {
        long nowMillis = timeMillisSupplier.getAsLong();
        long windowStartMillis = nowMillis - windowSizeMillis;
        // 查询时也会顺便清理过期记录，保证返回值代表“最近窗口内的真实数量”。
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
