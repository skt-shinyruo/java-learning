package yier.bubu.concurrency.ratelimit;

import org.junit.Assert;
import org.junit.Test;

/**
 * 令牌桶（Token Bucket）限流器 - 测试即文档。
 * <p>
 * 模型：
 * - 桶里最多有 capacity 个令牌
 * - 以固定速率 refillTokensPerSecond 往桶里加令牌（直到 capacity）
 * - 请求来消耗令牌，令牌不足则拒绝
 * <p>
 * 特点：既限制长期平均速率，又允许短时突发（burst=capacity）
 */
public class TokenBucketRateLimiterTest {
    @Test
    public void tokenBucket_allowsBurstUpToCapacity() {
        ManualClock clock = new ManualClock(0);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5.0, 1.0, clock);

        // 初始 tokens=capacity，允许一次性打满 5 个
        Assert.assertTrue(limiter.tryAcquire());
        Assert.assertTrue(limiter.tryAcquire());
        Assert.assertTrue(limiter.tryAcquire());
        Assert.assertTrue(limiter.tryAcquire());
        Assert.assertTrue(limiter.tryAcquire());
        Assert.assertFalse("桶空了，应拒绝", limiter.tryAcquire());
    }

    @Test
    public void tokenBucket_refillsAtFixedRate_overTime() {
        ManualClock clock = new ManualClock(0);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5.0, 1.0, clock);

        // 先把桶打空
        Assert.assertTrue(limiter.tryAcquire(5.0));
        Assert.assertFalse(limiter.tryAcquire());

        clock.advanceMillis(1_000);
        Assert.assertTrue("1 秒补 1 个令牌，应放行 1 次", limiter.tryAcquire());
        Assert.assertFalse("令牌又用完了，应拒绝", limiter.tryAcquire());

        clock.advanceMillis(10_000);
        Assert.assertTrue("补足后最多也只到 capacity=5，应允许一次性拿 5 个", limiter.tryAcquire(5.0));
        Assert.assertFalse("桶空了，应拒绝", limiter.tryAcquire(0.1));
    }
}
