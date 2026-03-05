package yier.bubu.concurrency.ratelimit;

import org.junit.Assert;
import org.junit.Test;

/**
 * 漏桶（Leaky Bucket）限流器 - 测试即文档。
 * <p>
 * 可以把它理解为“排队/整流”：
 * - 请求把 water（积压）加进桶里
 * - 桶以固定速率 leakPerSecond 漏出（积压减少）
 * - 桶满则拒绝
 */
public class LeakyBucketRateLimiterTest {
    @Test
    public void leakyBucket_shouldRejectWhenFull() {
        ManualClock clock = new ManualClock(0);
        LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(3.0, 1.0, clock);

        Assert.assertTrue(limiter.tryAcquire());
        Assert.assertTrue(limiter.tryAcquire());
        Assert.assertTrue(limiter.tryAcquire());
        Assert.assertFalse("桶满（capacity=3），应拒绝", limiter.tryAcquire());
    }

    @Test
    public void leakyBucket_leaksOverTime_andAllowsNewRequests() {
        ManualClock clock = new ManualClock(0);
        LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(3.0, 1.0, clock);

        Assert.assertTrue(limiter.tryAcquire(3.0));
        Assert.assertFalse(limiter.tryAcquire());

        clock.advanceMillis(1_000);
        Assert.assertTrue("1 秒漏出 1 个单位，应能再放行 1 个", limiter.tryAcquire(1.0));
        Assert.assertFalse("剩余容量不足，应拒绝", limiter.tryAcquire(1.0));

        clock.advanceMillis(5_000);
        Assert.assertTrue("漏一段时间后积压归零，应允许再次打满", limiter.tryAcquire(3.0));
        Assert.assertFalse("桶满了，应拒绝", limiter.tryAcquire(0.1));
    }
}
