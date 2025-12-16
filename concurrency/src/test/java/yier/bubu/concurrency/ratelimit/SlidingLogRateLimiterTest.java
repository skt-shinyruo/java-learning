package yier.bubu.concurrency.ratelimit;

import org.junit.Assert;
import org.junit.Test;

/**
 * 滑动日志（Sliding Window Log）限流器 - 测试即文档。
 * <p>
 * 它会记录每一次“放行”的时间戳，然后严格统计最近 windowSizeMillis 内的数量。
 * - 优点：最精确（严格滑动窗口）
 * - 缺点：高 QPS 时存储/计算开销大
 */
public class SlidingLogRateLimiterTest {
    @Test
    public void withinWindow_shouldRejectWhenLimitReached() {
        ManualClock clock = new ManualClock(100);
        SlidingLogRateLimiter limiter = new SlidingLogRateLimiter(3, 1_000, clock);

        Assert.assertTrue(limiter.tryAcquire());
        Assert.assertTrue(limiter.tryAcquire());
        Assert.assertTrue(limiter.tryAcquire());
        Assert.assertFalse("最近 1 秒内已满 3 次，应拒绝", limiter.tryAcquire());
    }

    @Test
    public void afterWindowExpires_shouldAllowAgain() {
        ManualClock clock = new ManualClock(100);
        SlidingLogRateLimiter limiter = new SlidingLogRateLimiter(3, 1_000, clock);

        Assert.assertTrue(limiter.tryAcquire());
        Assert.assertTrue(limiter.tryAcquire());
        Assert.assertTrue(limiter.tryAcquire());
        Assert.assertFalse(limiter.tryAcquire());

        clock.setMillis(1_099);
        Assert.assertFalse("仍在窗口内（窗口起点=99ms），应拒绝", limiter.tryAcquire());

        clock.setMillis(1_100);
        Assert.assertTrue("最早的请求时间戳=100ms 已过期，应重新放行", limiter.tryAcquire());
    }
}
