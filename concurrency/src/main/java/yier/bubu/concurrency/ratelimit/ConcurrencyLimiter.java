package yier.bubu.concurrency.ratelimit;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Concurrency limiting (Bulkhead):
 * Limit the number of in-flight (currently processing) requests.
 * <p>
 * Unlike QPS rate-limiting, this directly protects thread pools / connection pools under slow downstreams.
 */
public final class ConcurrencyLimiter {
    private final int maxInFlight;
    private final AtomicInteger inFlight;

    public ConcurrencyLimiter(int maxInFlight) {
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException("maxInFlight must be > 0");
        }
        this.maxInFlight = maxInFlight;
        this.inFlight = new AtomicInteger(0);
    }

    public Permit tryAcquire() {
        while (true) {
            int current = inFlight.get();
            if (current >= maxInFlight) {
                return null;
            }
            if (inFlight.compareAndSet(current, current + 1)) {
                return new Permit(this);
            }
        }
    }

    public int getMaxInFlight() {
        return maxInFlight;
    }

    public int getInFlight() {
        return inFlight.get();
    }

    private void release() {
        int after = inFlight.decrementAndGet();
        if (after < 0) {
            inFlight.incrementAndGet();
            throw new IllegalStateException("inFlight became negative");
        }
    }

    public static final class Permit implements AutoCloseable {
        private final ConcurrencyLimiter limiter;
        private final AtomicBoolean released;

        private Permit(ConcurrencyLimiter limiter) {
            this.limiter = limiter;
            this.released = new AtomicBoolean(false);
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                limiter.release();
            }
        }
    }
}

