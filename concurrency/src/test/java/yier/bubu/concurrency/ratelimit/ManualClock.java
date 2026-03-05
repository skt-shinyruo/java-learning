package yier.bubu.concurrency.ratelimit;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 手动可控的时间源（毫秒）。
 * <p>
 * 目的：让限流算法的测试不用 Thread.sleep，从而做到：
 * - 更快（毫秒级完成）
 * - 更稳定（不依赖机器负载/调度）
 * - 更可读（时间点由测试显式声明）
 */
final class ManualClock implements LongSupplier {
    private final AtomicLong nowMillis;

    ManualClock(long initialMillis) {
        this.nowMillis = new AtomicLong(initialMillis);
    }

    @Override
    public long getAsLong() {
        return nowMillis.get();
    }

    long nowMillis() {
        return nowMillis.get();
    }

    void setMillis(long millis) {
        nowMillis.set(millis);
    }

    void advanceMillis(long deltaMillis) {
        nowMillis.addAndGet(deltaMillis);
    }
}
