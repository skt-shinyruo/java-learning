package yier.bubu.concurrency.ratelimit;

import org.junit.Assert;
import org.junit.Test;

/**
 * 自适应限流（这里实现的是“自适应并发”）- 测试即文档。
 * <p>
 * 这是一个学习向实现：用非常简单的 AIMD（Additive Increase, Multiplicative Decrease）规则调节并发上限：
 * - 成功且延迟 <= targetLatencyMillis：limit + 1
 * - 失败或延迟过大：limit * decreaseFactor（并确保不低于 minLimit）
 * <p>
 * 注意：这不是工业级算法，只用于理解“自适应限流”的闭环思路。
 */
public class AdaptiveConcurrencyLimiterTest {
    @Test
    public void adaptiveLimiter_shouldEnforceCurrentInFlightLimit() {
        ManualClock clock = new ManualClock(0);
        AdaptiveConcurrencyLimiter limiter = new AdaptiveConcurrencyLimiter(2, 1, 4, 50, 0.7, clock);

        AdaptiveConcurrencyLimiter.Permit p1 = limiter.tryAcquire();
        AdaptiveConcurrencyLimiter.Permit p2 = limiter.tryAcquire();
        Assert.assertNotNull(p1);
        Assert.assertNotNull(p2);
        Assert.assertNull("并发已达当前 limit=2，应获取失败", limiter.tryAcquire());

        p1.close();
        p2.close();
        Assert.assertEquals(0, limiter.getInFlight());
    }

    @Test
    public void adaptiveLimiter_shouldIncreaseLimit_afterFastSuccess() {
        ManualClock clock = new ManualClock(0);
        AdaptiveConcurrencyLimiter limiter = new AdaptiveConcurrencyLimiter(2, 1, 4, 50, 0.7, clock);

        AdaptiveConcurrencyLimiter.Permit p1 = limiter.tryAcquire();
        Assert.assertNotNull(p1);
        Assert.assertEquals(1, limiter.getInFlight());
        Assert.assertEquals(2, limiter.getCurrentLimit());

        clock.advanceMillis(40);
        p1.onSuccess();
        Assert.assertEquals(0, limiter.getInFlight());
        Assert.assertEquals("快速成功应触发 +1", 3, limiter.getCurrentLimit());
    }

    @Test
    public void adaptiveLimiter_shouldDecreaseLimit_afterFailureOrSlowResponse() {
        ManualClock clock = new ManualClock(0);
        AdaptiveConcurrencyLimiter limiter = new AdaptiveConcurrencyLimiter(4, 1, 4, 50, 0.7, clock);

        AdaptiveConcurrencyLimiter.Permit p1 = limiter.tryAcquire();
        Assert.assertNotNull(p1);

        clock.advanceMillis(200);
        p1.onFailure();
        Assert.assertEquals(0, limiter.getInFlight());
        Assert.assertEquals("失败/慢请求应触发乘性下降（4*0.7=2.8 -> 2）", 2, limiter.getCurrentLimit());
    }
}
