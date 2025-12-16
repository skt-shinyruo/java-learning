package yier.bubu.concurrency.ratelimit;

import org.junit.Assert;
import org.junit.Test;

/**
 * 固定窗口计数（Fixed Window Counter）限流器 - 测试即文档。
 * <p>
 * 读这些测试你可以快速了解：
 * - 同一时间窗口内：最多放行 limit 次（或 permits 之和不超过 limit）
 * - 窗口滚动后：计数清零
 * - 缺点示例：窗口边界会“突刺”
 */
public class FixedWindowCounterRateLimiterTest {
    @Test
    public void withinSameWindow_shouldAllowUntilLimit_thenReject() {
        ManualClock clock = new ManualClock(0);
        FixedWindowCounterRateLimiter limiter = new FixedWindowCounterRateLimiter(3, 1_000, clock);

        Assert.assertTrue("第 1 次应该放行", limiter.tryAcquire());
        Assert.assertTrue("第 2 次应该放行", limiter.tryAcquire());
        Assert.assertTrue("第 3 次应该放行（达到 limit）", limiter.tryAcquire());
        Assert.assertFalse("第 4 次应该拒绝（超过 limit）", limiter.tryAcquire());
        Assert.assertEquals("窗口内计数应为 3", 3, limiter.getCountInWindow());
    }

    @Test
    public void windowRolls_shouldResetCounter() {
        ManualClock clock = new ManualClock(0);
        FixedWindowCounterRateLimiter limiter = new FixedWindowCounterRateLimiter(3, 1_000, clock);

        Assert.assertTrue(limiter.tryAcquire());
        Assert.assertTrue(limiter.tryAcquire());
        Assert.assertTrue(limiter.tryAcquire());
        Assert.assertFalse("窗口内已满，应该拒绝", limiter.tryAcquire());

        clock.setMillis(999);
        Assert.assertFalse("仍在同一个窗口内，仍应拒绝", limiter.tryAcquire());

        clock.setMillis(1_000);
        Assert.assertTrue("进入新窗口后计数清零，应重新放行", limiter.tryAcquire());
        Assert.assertEquals(1, limiter.getCountInWindow());
    }

    @Test
    public void permits_shouldBeCountedAtomically() {
        ManualClock clock = new ManualClock(123);
        FixedWindowCounterRateLimiter limiter = new FixedWindowCounterRateLimiter(3, 1_000, clock);

        Assert.assertTrue("一次性申请 2 个配额应该成功", limiter.tryAcquire(2));
        Assert.assertFalse("再申请 2 个会超过 limit，应拒绝（不会部分放行）", limiter.tryAcquire(2));
        Assert.assertTrue("只申请 1 个仍在 limit 范围内，应放行", limiter.tryAcquire(1));
        Assert.assertFalse("再次申请 1 个会超过 limit，应拒绝", limiter.tryAcquire(1));
    }

    @Test
    public void boundarySpike_example_twoAdjacentWindowsCanBothBeFilled() {
        ManualClock clock = new ManualClock(999);
        FixedWindowCounterRateLimiter limiter = new FixedWindowCounterRateLimiter(3, 1_000, clock);

        int accepted = 0;
        for (int i = 0; i < 3; i++) {
            if (limiter.tryAcquire()) {
                accepted++;
            }
        }
        Assert.assertEquals("窗口末尾可以打满一次", 3, accepted);

        // 1ms 后进入下一个窗口，计数清零 —— 这就是固定窗口“突刺”的根源。
        clock.setMillis(1_000);
        for (int i = 0; i < 3; i++) {
            if (limiter.tryAcquire()) {
                accepted++;
            }
        }
        Assert.assertEquals("相邻窗口也能再打满一次，总共放行 6 次（突刺示例）", 6, accepted);
    }
}
