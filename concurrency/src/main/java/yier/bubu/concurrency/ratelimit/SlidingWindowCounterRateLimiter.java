package yier.bubu.concurrency.ratelimit;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Sliding Window Counter (Rolling Window Counter):
 * Split a big window into multiple small buckets (e.g. 1s => 10 * 100ms) and sum bucket counts.
 * <p>
 * It's an approximation: the smaller the buckets, the more accurate (and the more overhead).
 */
public final class SlidingWindowCounterRateLimiter {
    private static final long UNUSED_BUCKET = Long.MIN_VALUE;

    private final int limit;
    private final long windowSizeMillis;
    private final int bucketCount;
    private final long bucketSizeMillis;
    private final LongSupplier timeMillisSupplier;

    private final long[] bucketStartMillis;
    private final int[] bucketCounters;

    public SlidingWindowCounterRateLimiter(int limit, long windowSizeMillis, int bucketCount) {
        this(limit, windowSizeMillis, bucketCount, System::currentTimeMillis);
    }

    SlidingWindowCounterRateLimiter(int limit, long windowSizeMillis, int bucketCount, LongSupplier timeMillisSupplier) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        if (windowSizeMillis <= 0) {
            throw new IllegalArgumentException("windowSizeMillis must be > 0");
        }
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be > 0");
        }
        if (windowSizeMillis % bucketCount != 0) {
            throw new IllegalArgumentException("windowSizeMillis must be divisible by bucketCount");
        }
        this.limit = limit;
        this.windowSizeMillis = windowSizeMillis;
        this.bucketCount = bucketCount;
        this.bucketSizeMillis = windowSizeMillis / bucketCount;
        this.timeMillisSupplier = Objects.requireNonNull(timeMillisSupplier, "timeMillisSupplier");

        this.bucketStartMillis = new long[bucketCount];
        this.bucketCounters = new int[bucketCount];
        Arrays.fill(this.bucketStartMillis, UNUSED_BUCKET);
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
        long currentBucketId = Math.floorDiv(nowMillis, bucketSizeMillis);
        int index = (int) Math.floorMod(currentBucketId, bucketCount);
        long currentBucketStart = currentBucketId * bucketSizeMillis;

        if (bucketStartMillis[index] != currentBucketStart) {
            bucketStartMillis[index] = currentBucketStart;
            bucketCounters[index] = 0;
        }

        long windowStartMillis = nowMillis - windowSizeMillis;
        int total = 0;
        for (int i = 0; i < bucketCount; i++) {
            long start = bucketStartMillis[i];
            if (start == UNUSED_BUCKET) {
                continue;
            }
            long bucketEndMillis = start + bucketSizeMillis;
            if (bucketEndMillis <= windowStartMillis) {
                continue;
            }
            total += bucketCounters[i];
            if (total >= limit) {
                return false;
            }
        }

        if (total + permits > limit) {
            return false;
        }
        bucketCounters[index] += permits;
        return true;
    }

    public synchronized int getCurrentSum() {
        long nowMillis = timeMillisSupplier.getAsLong();
        long windowStartMillis = nowMillis - windowSizeMillis;
        int total = 0;
        for (int i = 0; i < bucketCount; i++) {
            long start = bucketStartMillis[i];
            if (start == UNUSED_BUCKET) {
                continue;
            }
            long bucketEndMillis = start + bucketSizeMillis;
            if (bucketEndMillis <= windowStartMillis) {
                continue;
            }
            total += bucketCounters[i];
        }
        return total;
    }
}
