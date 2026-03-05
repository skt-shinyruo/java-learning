package yier.bubu.concurrency.ratelimit;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Adaptive limiting (simple adaptive concurrency):
 * Adjust max in-flight dynamically based on latency / failures.
 * <p>
 * This is a learning-oriented implementation using a very simple AIMD rule:
 * - if success && latency <= target => increase limit (additive)
 * - else => decrease limit (multiplicative)
 */
public final class AdaptiveConcurrencyLimiter {
    private final int minLimit;
    private final int maxLimit;
    private final long targetLatencyMillis;
    private final double decreaseFactor;
    private final LongSupplier timeMillisSupplier;

    private final AtomicInteger currentLimit;
    private final AtomicInteger inFlight;

    public AdaptiveConcurrencyLimiter(int initialLimit, int minLimit, int maxLimit, long targetLatencyMillis) {
        this(initialLimit, minLimit, maxLimit, targetLatencyMillis, 0.7, System::currentTimeMillis);
    }

    AdaptiveConcurrencyLimiter(
            int initialLimit,
            int minLimit,
            int maxLimit,
            long targetLatencyMillis,
            double decreaseFactor,
            LongSupplier timeMillisSupplier
    ) {
        if (minLimit <= 0) {
            throw new IllegalArgumentException("minLimit must be > 0");
        }
        if (maxLimit < minLimit) {
            throw new IllegalArgumentException("maxLimit must be >= minLimit");
        }
        if (initialLimit < minLimit || initialLimit > maxLimit) {
            throw new IllegalArgumentException("initialLimit must be in [minLimit, maxLimit]");
        }
        if (targetLatencyMillis <= 0) {
            throw new IllegalArgumentException("targetLatencyMillis must be > 0");
        }
        if (!(decreaseFactor > 0.0 && decreaseFactor < 1.0)) {
            throw new IllegalArgumentException("decreaseFactor must be in (0, 1)");
        }

        this.minLimit = minLimit;
        this.maxLimit = maxLimit;
        this.targetLatencyMillis = targetLatencyMillis;
        this.decreaseFactor = decreaseFactor;
        this.timeMillisSupplier = Objects.requireNonNull(timeMillisSupplier, "timeMillisSupplier");

        this.currentLimit = new AtomicInteger(initialLimit);
        this.inFlight = new AtomicInteger(0);
    }

    public Permit tryAcquire() {
        // 与 ConcurrencyLimiter 类似：在当前 limit 限制内，抢占一个 in-flight 名额。
        while (true) {
            int limitSnapshot = currentLimit.get();
            int current = inFlight.get();
            if (current >= limitSnapshot) {
                return null;
            }
            if (inFlight.compareAndSet(current, current + 1)) {
                // startMillis 用于后续计算“这次请求的延迟”，作为自适应调参的信号。
                return new Permit(this, timeMillisSupplier.getAsLong());
            }
        }
    }

    public int getCurrentLimit() {
        return currentLimit.get();
    }

    public int getInFlight() {
        return inFlight.get();
    }

    private void onCompletion(long startMillis, boolean success) {
        long now = timeMillisSupplier.getAsLong();
        long latencyMillis = Math.max(0L, now - startMillis);

        // 先释放 in-flight 名额：无论成功/失败，都不应该占用并发配额。
        inFlight.decrementAndGet();

        // 调整 limit 需要串行化，否则高并发下多个线程同时“加/减”会导致震荡更明显。
        synchronized (this) {
            int limit = currentLimit.get();
            int newLimit = limit;
            if (success && latencyMillis <= targetLatencyMillis) {
                if (limit < maxLimit) {
                    newLimit = limit + 1;
                }
            } else {
                // 乘性下降：失败/慢请求时快速收缩并发，保护下游。
                newLimit = Math.max(minLimit, (int) Math.floor(limit * decreaseFactor));
                if (newLimit == limit && limit > minLimit) {
                    newLimit = limit - 1;
                }
            }
            currentLimit.set(newLimit);
        }
    }

    public static final class Permit implements AutoCloseable {
        private final AdaptiveConcurrencyLimiter limiter;
        private final long startMillis;
        private final AtomicBoolean completed;

        private Permit(AdaptiveConcurrencyLimiter limiter, long startMillis) {
            this.limiter = limiter;
            this.startMillis = startMillis;
            this.completed = new AtomicBoolean(false);
        }

        public void onSuccess() {
            complete(true);
        }

        public void onFailure() {
            complete(false);
        }

        @Override
        public void close() {
            // 为了方便 try-with-resources，这里把 close() 视为成功完成。
            // 如果你需要表达失败，请显式调用 onFailure()。
            onSuccess();
        }

        private void complete(boolean success) {
            if (completed.compareAndSet(false, true)) {
                limiter.onCompletion(startMillis, success);
            }
        }
    }
}
