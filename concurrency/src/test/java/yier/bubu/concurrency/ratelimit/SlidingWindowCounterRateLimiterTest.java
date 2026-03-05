package yier.bubu.concurrency.ratelimit;

import org.junit.Assert;
import org.junit.Test;

/**
 * 滑动窗口计数（Rolling Window Counter）限流器 - 测试即文档。
 * <p>
 * 它把一个大窗口拆成多个小桶（bucket），统计“最近 windowSizeMillis 内”的桶计数总和。
 * - 优点：比固定窗口更平滑
 * - 缺点：是近似值（桶越细越准、开销越大）
 */
public class SlidingWindowCounterRateLimiterTest {
    @Test
    public void rollingWindow_shouldSmoothFixedWindowBoundarySpike() {
        ManualClock clock = new ManualClock(950);
        SlidingWindowCounterRateLimiter limiter = new SlidingWindowCounterRateLimiter(3, 1_000, 10, clock);

        Assert.assertTrue(limiter.tryAcquire());
        Assert.assertTrue(limiter.tryAcquire());
        Assert.assertTrue(limiter.tryAcquire());
        Assert.assertFalse("同一滚动窗口内超过 limit，应拒绝", limiter.tryAcquire());

        // 固定窗口在 1000ms 处会清零，但滚动窗口仍会把 950ms 附近的桶算进来，因此仍拒绝。
        clock.setMillis(1_000);
        Assert.assertFalse("窗口边界处不会立刻放开（更平滑）", limiter.tryAcquire());

        clock.setMillis(2_000);
        Assert.assertTrue("当旧桶完全滑出窗口后，应重新放行", limiter.tryAcquire());
    }

    @Test
    public void rollingWindow_isApproximate_exampleComparedToSlidingLog() {
        // 这个用例用“滑动日志（严格）”作为对照，展示桶计数的近似性。
        //
        // 场景：
        // - 在 999ms 时瞬间打满 3 次
        // - 在 1950ms 时，严格滑动窗口仍应把 999ms 这 3 次算在最近 1000ms 内（窗口起点=950ms）
        // - 但桶计数在桶边界附近可能出现偏差（取整 / 桶滚动），导致提前放行
        ManualClock clock = new ManualClock(999);
        SlidingWindowCounterRateLimiter rolling = new SlidingWindowCounterRateLimiter(3, 1_000, 10, clock);
        SlidingLogRateLimiter strict = new SlidingLogRateLimiter(3, 1_000, clock);

        for (int i = 0; i < 3; i++) {
            Assert.assertTrue("两种算法在未满时都应放行", rolling.tryAcquire());
            Assert.assertTrue("两种算法在未满时都应放行", strict.tryAcquire());
        }
        Assert.assertFalse("滚动桶计数在同一时刻超过 limit 应拒绝", rolling.tryAcquire());
        Assert.assertFalse("严格滑动窗口在同一时刻超过 limit 应拒绝", strict.tryAcquire());

        clock.setMillis(1_950);
        Assert.assertFalse("严格滑动窗口：999ms 仍在 (950,1950] 内，应拒绝", strict.tryAcquire());

        // 注意：这里 rolling 可能会放行，这是“桶化近似”在窗口边缘的典型表现。
        Assert.assertTrue("桶计数是近似的：在桶边界附近可能提前放行", rolling.tryAcquire());
    }
}
