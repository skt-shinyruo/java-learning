package yier.bubu.concurrency.ratelimit;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Leaky Bucket:
 * Requests add "water" (backlog) into a bucket. The bucket leaks at a constant rate.
 * Reject when the bucket is full.
 * <p>
 * This gives a smoother output rate (more like queueing).
 */
public final class LeakyBucketRateLimiter {
    private final double capacity;
    private final double leakPerMillis;
    private final LongSupplier timeMillisSupplier;

    private double water;
    private long lastUpdateMillis;

    public LeakyBucketRateLimiter(double capacity, double leakPerSecond) {
        this(capacity, leakPerSecond, System::currentTimeMillis);
    }

    LeakyBucketRateLimiter(double capacity, double leakPerSecond, LongSupplier timeMillisSupplier) {
        if (!(capacity > 0.0)) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (!(leakPerSecond > 0.0)) {
            throw new IllegalArgumentException("leakPerSecond must be > 0");
        }
        this.capacity = capacity;
        this.leakPerMillis = leakPerSecond / 1_000.0;
        this.timeMillisSupplier = Objects.requireNonNull(timeMillisSupplier, "timeMillisSupplier");

        long now = timeMillisSupplier.getAsLong();
        this.lastUpdateMillis = now;
        this.water = 0.0;
    }

    public synchronized boolean tryAcquire() {
        return tryAcquire(1.0);
    }

    public synchronized boolean tryAcquire(double permits) {
        if (!(permits > 0.0)) {
            throw new IllegalArgumentException("permits must be > 0");
        }
        if (permits > capacity) {
            return false;
        }

        leak();
        if (water + permits > capacity) {
            return false;
        }
        water += permits;
        return true;
    }

    public synchronized double getWater() {
        leak();
        return water;
    }

    private void leak() {
        long now = timeMillisSupplier.getAsLong();
        long elapsedMillis = now - lastUpdateMillis;
        if (elapsedMillis <= 0) {
            return;
        }

        double leaked = elapsedMillis * leakPerMillis;
        water = Math.max(0.0, water - leaked);
        lastUpdateMillis = now;
    }
}

