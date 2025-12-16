package yier.bubu.concurrency.ratelimit;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Token Bucket:
 * Refill tokens at a fixed rate up to capacity. Each request consumes tokens.
 * Allows short bursts (capacity) while limiting long-term average rate (refill rate).
 */
public final class TokenBucketRateLimiter {
    private final double capacity;
    private final double refillTokensPerMillis;
    private final LongSupplier timeMillisSupplier;

    private double tokens;
    private long lastRefillMillis;

    public TokenBucketRateLimiter(double capacity, double refillTokensPerSecond) {
        this(capacity, refillTokensPerSecond, System::currentTimeMillis);
    }

    TokenBucketRateLimiter(double capacity, double refillTokensPerSecond, LongSupplier timeMillisSupplier) {
        if (!(capacity > 0.0)) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (!(refillTokensPerSecond > 0.0)) {
            throw new IllegalArgumentException("refillTokensPerSecond must be > 0");
        }
        this.capacity = capacity;
        this.refillTokensPerMillis = refillTokensPerSecond / 1_000.0;
        this.timeMillisSupplier = Objects.requireNonNull(timeMillisSupplier, "timeMillisSupplier");

        long now = timeMillisSupplier.getAsLong();
        this.lastRefillMillis = now;
        this.tokens = capacity;
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

        refill();
        if (tokens < permits) {
            return false;
        }
        tokens -= permits;
        return true;
    }

    public synchronized double getTokens() {
        refill();
        return tokens;
    }

    private void refill() {
        long now = timeMillisSupplier.getAsLong();
        long elapsedMillis = now - lastRefillMillis;
        if (elapsedMillis <= 0) {
            return;
        }

        double added = elapsedMillis * refillTokensPerMillis;
        tokens = Math.min(capacity, tokens + added);
        lastRefillMillis = now;
    }
}

