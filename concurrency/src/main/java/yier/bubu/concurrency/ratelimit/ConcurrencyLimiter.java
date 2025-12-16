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
        // 典型的 CAS 自旋：在不加锁的情况下“抢占一个并发名额”。
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
            // 理论上不会发生：Permit.close() 具备幂等保护。
            // 这里作为防御性检查，避免未来改动引入的计数错误悄悄扩散。
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
            // close() 允许被多次调用（幂等）；只在第一次释放名额。
            if (released.compareAndSet(false, true)) {
                limiter.release();
            }
        }
    }
}
