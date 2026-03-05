package yier.bubu.concurrency.ratelimit;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * 单机（单进程）限流算法的“尽量纯 JDK”实现集合。
 *
 * <p>编译：javac 限流/RateLimiters.java
 * <p>运行示例：java -cp 限流 RateLimiters
 */
public final class RateLimiters {
    private RateLimiters() {
    }

    /**
     * 最基础的“按请求次数”限流接口：一次请求=一次 tryAcquire。
     */
    public interface RateLimiter {
        boolean tryAcquire();
    }

    /**
     * 固定窗口计数（Fixed Window Counter）：窗口边界有突刺。
     */
    public static final class FixedWindowCounter implements RateLimiter {
        private final int limit;
        private final long windowNanos;
        private long windowStartNanos;
        private int counter;

        public FixedWindowCounter(int limit, Duration window) {
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be > 0");
            }
            this.windowNanos = positiveNanos(window, "window");
            this.limit = limit;
            this.windowStartNanos = System.nanoTime();
            this.counter = 0;
        }

        @Override
        public synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            if (now - windowStartNanos >= windowNanos) {
                windowStartNanos = now;
                counter = 0;
            }
            if (counter >= limit) {
                return false;
            }
            counter++;
            return true;
        }
    }

    /**
     * 滑动窗口计数（Sliding Window Counter / Rolling Window）：把窗口拆成多个 bucket 做近似。
     *
     * <p>bucketCount 越大越平滑、开销越高；典型：window=1s, bucketCount=10。
     */
    public static final class SlidingWindowCounter implements RateLimiter {
        private final int limit;
        private final long windowNanos;
        private final long bucketNanos;
        private final long[] bucketStartNanos;
        private final int[] bucketCounters;

        public SlidingWindowCounter(int limit, Duration window, int bucketCount) {
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be > 0");
            }
            if (bucketCount <= 0) {
                throw new IllegalArgumentException("bucketCount must be > 0");
            }
            this.windowNanos = positiveNanos(window, "window");
            if (windowNanos < bucketCount) {
                throw new IllegalArgumentException("bucketCount is too large for the window");
            }
            this.bucketNanos = windowNanos / bucketCount;
            this.limit = limit;
            this.bucketStartNanos = new long[bucketCount];
            this.bucketCounters = new int[bucketCount];
        }

        @Override
        public synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            long currentBucketStart = now - floorMod(now, bucketNanos);
            long currentBucketId = currentBucketStart / bucketNanos;
            int idx = (int) Math.floorMod(currentBucketId, bucketStartNanos.length);

            if (bucketStartNanos[idx] != currentBucketStart) {
                bucketStartNanos[idx] = currentBucketStart;
                bucketCounters[idx] = 0;
            }

            long threshold = currentBucketStart - windowNanos;
            int sum = 0;
            for (int i = 0; i < bucketStartNanos.length; i++) {
                if (bucketStartNanos[i] >= threshold) {
                    sum += bucketCounters[i];
                }
            }

            if (sum >= limit) {
                return false;
            }
            bucketCounters[idx]++;
            return true;
        }
    }

    /**
     * 滑动日志（Sliding Log）：精确滑动窗口，但需要存每次请求时间戳（高 QPS 时更重）。
     */
    public static final class SlidingWindowLog implements RateLimiter {
        private final int limit;
        private final long windowNanos;
        private final Deque<Long> timestamps = new ArrayDeque<>();

        public SlidingWindowLog(int limit, Duration window) {
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be > 0");
            }
            this.windowNanos = positiveNanos(window, "window");
            this.limit = limit;
        }

        @Override
        public synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= windowNanos) {
                timestamps.removeFirst();
            }
            if (timestamps.size() >= limit) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    /**
     * 令牌桶（Token Bucket）：限制平均速率，但允许短时突发（由 capacity 决定）。
     */
    public static final class TokenBucket implements RateLimiter {
        private final long capacity;
        private final double refillTokensPerNano;
        private double tokens;
        private long lastRefillNanos;

        /**
         * @param capacity              桶容量（允许突发的上限）
         * @param refillTokensPerSecond 每秒补充多少令牌（长期平均速率）
         */
        public TokenBucket(long capacity, double refillTokensPerSecond) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be > 0");
            }
            if (refillTokensPerSecond <= 0) {
                throw new IllegalArgumentException("refillTokensPerSecond must be > 0");
            }
            this.capacity = capacity;
            this.refillTokensPerNano = refillTokensPerSecond / 1_000_000_000D;
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        @Override
        public synchronized boolean tryAcquire() {
            refill();
            if (tokens < 1D) {
                return false;
            }
            tokens -= 1D;
            return true;
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNanos;
            if (elapsed <= 0) {
                return;
            }
            double added = elapsed * refillTokensPerNano;
            tokens = Math.min(capacity, tokens + added);
            lastRefillNanos = now;
        }
    }

    /**
     * 漏桶（Leaky Bucket）：以恒定速率“漏出”，请求相当于往桶里加水；桶满拒绝。
     *
     * <p>在“只做 tryAcquire 不排队”的语境下，它的外在表现是：突发时更倾向于拒绝，从而让通过的请求更平滑。
     */
    public static final class LeakyBucket implements RateLimiter {
        private final long capacity;
        private final double leakPerNano;
        private double water;
        private long lastLeakNanos;

        /**
         * @param capacity      桶容量（最多允许多少积压）
         * @param leakPerSecond 每秒漏出多少“水”（恒定处理速率）
         */
        public LeakyBucket(long capacity, double leakPerSecond) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be > 0");
            }
            if (leakPerSecond <= 0) {
                throw new IllegalArgumentException("leakPerSecond must be > 0");
            }
            this.capacity = capacity;
            this.leakPerNano = leakPerSecond / 1_000_000_000D;
            this.water = 0D;
            this.lastLeakNanos = System.nanoTime();
        }

        @Override
        public synchronized boolean tryAcquire() {
            leak();
            if (water + 1D > capacity) {
                return false;
            }
            water += 1D;
            return true;
        }

        private void leak() {
            long now = System.nanoTime();
            long elapsed = now - lastLeakNanos;
            if (elapsed <= 0) {
                return;
            }
            double leaked = elapsed * leakPerNano;
            water = Math.max(0D, water - leaked);
            lastLeakNanos = now;
        }
    }

    /**
     * 并发限流（Concurrency Limiting / Bulkhead）：限制同一时刻 in-flight 的请求数。
     *
     * <p>用法推荐：tryAcquirePermit + try-with-resources，避免忘记 release。
     */
    public static final class ConcurrencyLimiter {
        private final Semaphore semaphore;

        public ConcurrencyLimiter(int maxConcurrent) {
            this(maxConcurrent, false);
        }

        public ConcurrencyLimiter(int maxConcurrent, boolean fair) {
            if (maxConcurrent <= 0) {
                throw new IllegalArgumentException("maxConcurrent must be > 0");
            }
            this.semaphore = new Semaphore(maxConcurrent, fair);
        }

        public boolean tryAcquire() {
            return semaphore.tryAcquire();
        }

        public void release() {
            semaphore.release();
        }

        public Permit tryAcquirePermit() {
            if (!semaphore.tryAcquire()) {
                return null;
            }
            return new Permit(this);
        }

        public static final class Permit implements AutoCloseable {
            private final ConcurrencyLimiter limiter;
            private boolean closed;

            private Permit(ConcurrencyLimiter limiter) {
                this.limiter = limiter;
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                limiter.release();
            }
        }
    }

    /**
     * 有界队列限流（Queue-based / Bounded Queue）：把“突发”转成排队，队列满则拒绝。
     *
     * <p>本质上是：并发（线程数） + 有界队列（积压上限） + 拒绝策略。
     */
    public static final class BoundedQueueExecutor {
        private final ThreadPoolExecutor executor;

        public BoundedQueueExecutor(int maxConcurrent, int maxQueueSize) {
            this(maxConcurrent, maxQueueSize, new NamedThreadFactory("bounded-queue"));
        }

        public BoundedQueueExecutor(int maxConcurrent, int maxQueueSize, ThreadFactory threadFactory) {
            if (maxConcurrent <= 0) {
                throw new IllegalArgumentException("maxConcurrent must be > 0");
            }
            if (maxQueueSize <= 0) {
                throw new IllegalArgumentException("maxQueueSize must be > 0");
            }
            Objects.requireNonNull(threadFactory, "threadFactory");
            this.executor = new ThreadPoolExecutor(maxConcurrent, maxConcurrent, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(maxQueueSize), threadFactory, new ThreadPoolExecutor.AbortPolicy());
        }

        public boolean trySubmit(Runnable task) {
            Objects.requireNonNull(task, "task");
            try {
                executor.execute(task);
                return true;
            } catch (RejectedExecutionException rejected) {
                return false;
            }
        }

        public <T> Future<T> trySubmit(Callable<T> task) {
            Objects.requireNonNull(task, "task");
            FutureTask<T> future = new FutureTask<>(task);
            try {
                executor.execute(future);
                return future;
            } catch (RejectedExecutionException rejected) {
                return null;
            }
        }

        public int activeCount() {
            return executor.getActiveCount();
        }

        public int queueSize() {
            return executor.getQueue().size();
        }

        public void shutdown() {
            executor.shutdown();
        }

        public void shutdownNow() {
            executor.shutdownNow();
        }
    }

    /**
     * 自适应并发限流（Adaptive Concurrency Limiting）：根据延迟/失败做 AIMD 调整并发上限。
     *
     * <p>这是一种简化版：统计一段时间内的平均 RTT 和失败数：
     * <ul>
     *   <li>如果失败>0 或 avgRtt > targetRtt：并发上限减半（MD）</li>
     *   <li>否则：并发上限 +1（AI）</li>
     * </ul>
     *
     * <p>注意：降低上限不会“抢占”已经在执行的请求，只会阻止新的请求进入，直到 inFlight 回落。
     */
    public static final class AdaptiveConcurrencyLimiter {
        private final AtomicInteger inFlight = new AtomicInteger(0);
        private final AtomicInteger limit;
        private final int minLimit;
        private final int maxLimit;
        private final long targetRttNanos;
        private final long updateIntervalNanos;
        private volatile long lastUpdateNanos;

        private final LongAdder sampleCount = new LongAdder();
        private final LongAdder failureCount = new LongAdder();
        private final LongAdder latencySumNanos = new LongAdder();

        public AdaptiveConcurrencyLimiter(int initialLimit, int minLimit, int maxLimit, Duration targetRtt, Duration updateInterval) {
            if (minLimit <= 0) {
                throw new IllegalArgumentException("minLimit must be > 0");
            }
            if (maxLimit < minLimit) {
                throw new IllegalArgumentException("maxLimit must be >= minLimit");
            }
            if (initialLimit < minLimit || initialLimit > maxLimit) {
                throw new IllegalArgumentException("initialLimit must be within [minLimit, maxLimit]");
            }
            this.minLimit = minLimit;
            this.maxLimit = maxLimit;
            this.limit = new AtomicInteger(initialLimit);
            this.targetRttNanos = positiveNanos(targetRtt, "targetRtt");
            this.updateIntervalNanos = positiveNanos(updateInterval, "updateInterval");
            this.lastUpdateNanos = System.nanoTime();
        }

        public boolean tryAcquire() {
            while (true) {
                int currentInFlight = inFlight.get();
                int currentLimit = limit.get();
                if (currentInFlight >= currentLimit) {
                    return false;
                }
                if (inFlight.compareAndSet(currentInFlight, currentInFlight + 1)) {
                    return true;
                }
            }
        }

        public void release() {
            int after = inFlight.decrementAndGet();
            if (after < 0) {
                inFlight.incrementAndGet();
                throw new IllegalStateException("inFlight became negative (release called too many times)");
            }
        }

        public int currentLimit() {
            return limit.get();
        }

        public int inFlight() {
            return inFlight.get();
        }

        public Permit tryAcquirePermit() {
            if (!tryAcquire()) {
                return null;
            }
            return new Permit(this);
        }

        private void onRequestComplete(long latencyNanos, boolean success) {
            sampleCount.increment();
            latencySumNanos.add(Math.max(0L, latencyNanos));
            if (!success) {
                failureCount.increment();
            }
            maybeUpdateLimit();
        }

        private void maybeUpdateLimit() {
            long now = System.nanoTime();
            if (now - lastUpdateNanos < updateIntervalNanos) {
                return;
            }
            synchronized (this) {
                if (now - lastUpdateNanos < updateIntervalNanos) {
                    return;
                }

                long samples = sampleCount.sumThenReset();
                long failures = failureCount.sumThenReset();
                long latencySum = latencySumNanos.sumThenReset();
                lastUpdateNanos = now;

                if (samples <= 0) {
                    return;
                }
                long avgLatency = latencySum / samples;

                int current = limit.get();
                int next;
                if (failures > 0 || avgLatency > targetRttNanos) {
                    next = Math.max(minLimit, current / 2);
                } else {
                    next = Math.min(maxLimit, current + 1);
                }
                if (next != current) {
                    limit.set(next);
                }
            }
        }

        public final class Permit implements AutoCloseable {
            private final AdaptiveConcurrencyLimiter limiter;
            private final long startNanos;
            private boolean success = true;
            private boolean closed;

            private Permit(AdaptiveConcurrencyLimiter limiter) {
                this.limiter = limiter;
                this.startNanos = System.nanoTime();
            }

            public Permit markFailed() {
                this.success = false;
                return this;
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                long latency = System.nanoTime() - startNanos;
                limiter.release();
                limiter.onRequestComplete(latency, success);
            }
        }
    }

    /**
     * “按 key 限流”的简单封装（比如按 userId / ip / apiKey）：每个 key 维护一个 limiter 实例。
     *
     * <p>注意：单机内存方案要考虑 key 数量，建议配合 evictIdle 做淘汰，避免内存涨到不可控。
     */
    public static final class Keyed<K> {
        private final ConcurrentHashMap<K, Entry> map = new ConcurrentHashMap<>();
        private final Supplier<? extends RateLimiter> supplier;

        public Keyed(Supplier<? extends RateLimiter> supplier) {
            this.supplier = Objects.requireNonNull(supplier, "supplier");
        }

        public boolean tryAcquire(K key) {
            Objects.requireNonNull(key, "key");
            long now = System.nanoTime();
            Entry entry = map.computeIfAbsent(key, k -> new Entry(supplier.get(), now));
            entry.lastAccessNanos = now;
            return entry.limiter.tryAcquire();
        }

        public int size() {
            return map.size();
        }

        public int evictIdle(Duration maxIdle) {
            long maxIdleNanos = positiveNanos(maxIdle, "maxIdle");
            long now = System.nanoTime();
            int removed = 0;
            for (Map.Entry<K, Entry> e : map.entrySet()) {
                Entry entry = e.getValue();
                if (now - entry.lastAccessNanos > maxIdleNanos) {
                    if (map.remove(e.getKey(), entry)) {
                        removed++;
                    }
                }
            }
            return removed;
        }

        private final class Entry {
            private final RateLimiter limiter;
            private volatile long lastAccessNanos;

            private Entry(RateLimiter limiter, long lastAccessNanos) {
                this.limiter = Objects.requireNonNull(limiter, "limiter");
                this.lastAccessNanos = lastAccessNanos;
            }
        }
    }

    private static long positiveNanos(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return duration.toNanos();
    }

    private static long floorMod(long x, long y) {
        long r = x % y;
        return r >= 0 ? r : r + y;
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger seq = new AtomicInteger(0);

        private NamedThreadFactory(String prefix) {
            this.prefix = Objects.requireNonNull(prefix, "prefix");
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setName(prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    public static void main(String[] args) throws Exception {
        RateLimiter fixed = new FixedWindowCounter(5, Duration.ofSeconds(1));
        RateLimiter sliding = new SlidingWindowCounter(5, Duration.ofSeconds(1), 10);
        RateLimiter log = new SlidingWindowLog(5, Duration.ofSeconds(1));
        RateLimiter tokenBucket = new TokenBucket(10, 5); // capacity=10, refill=5 tokens/s
        RateLimiter leakyBucket = new LeakyBucket(10, 5); // capacity=10, leak=5 req/s

        System.out.println("FixedWindowCounter:");
        demoBurst(fixed);
        System.out.println("\nSlidingWindowCounter:");
        demoBurst(sliding);
        System.out.println("\nSlidingWindowLog:");
        demoBurst(log);
        System.out.println("\nTokenBucket:");
        demoBurst(tokenBucket);
        System.out.println("\nLeakyBucket:");
        demoBurst(leakyBucket);

        System.out.println("\nConcurrencyLimiter:");
        ConcurrencyLimiter concurrency = new ConcurrencyLimiter(2);
        try (ConcurrencyLimiter.Permit p1 = concurrency.tryAcquirePermit(); ConcurrencyLimiter.Permit p2 = concurrency.tryAcquirePermit()) {
            System.out.println("acquire 2 permits => " + (p1 != null && p2 != null));
            System.out.println("try 3rd permit => " + (concurrency.tryAcquirePermit() != null));
        }
        System.out.println("after close, try permit => " + (concurrency.tryAcquirePermit() != null));

        System.out.println("\nBoundedQueueExecutor:");
        BoundedQueueExecutor queueExec = new BoundedQueueExecutor(1, 2);
        boolean ok1 = queueExec.trySubmit(() -> sleepSilently(200));
        boolean ok2 = queueExec.trySubmit(() -> sleepSilently(200));
        boolean ok3 = queueExec.trySubmit(() -> sleepSilently(200));
        boolean ok4 = queueExec.trySubmit(() -> sleepSilently(200)); // likely rejected (1 active + 2 queued)
        System.out.println("submit results => " + ok1 + "," + ok2 + "," + ok3 + "," + ok4);
        queueExec.shutdownNow();

        System.out.println("\nAdaptiveConcurrencyLimiter:");
        AdaptiveConcurrencyLimiter adaptive = new AdaptiveConcurrencyLimiter(4, 1, 64, Duration.ofMillis(50), Duration.ofMillis(200));
        for (int i = 0; i < 30; i++) {
            try (AdaptiveConcurrencyLimiter.Permit p = adaptive.tryAcquirePermit()) {
                if (p == null) {
                    Thread.sleep(10);
                    continue;
                }
                // 模拟：前半段快且成功，后半段慢且偶发失败
                if (i < 15) {
                    Thread.sleep(10);
                } else {
                    Thread.sleep(80);
                    if (i % 5 == 0) {
                        p.markFailed();
                    }
                }
            }
            if (i % 5 == 0) {
                System.out.println("limit=" + adaptive.currentLimit() + ", inFlight=" + adaptive.inFlight());
            }
        }
    }

    private static void demoBurst(RateLimiter limiter) throws InterruptedException {
        for (int i = 0; i < 12; i++) {
            System.out.print(limiter.tryAcquire() ? "Y" : "N");
        }
        System.out.println(" (burst)");
        Thread.sleep(1100);
        for (int i = 0; i < 12; i++) {
            System.out.print(limiter.tryAcquire() ? "Y" : "N");
        }
        System.out.println(" (after sleep)");
    }

    private static void sleepSilently(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
