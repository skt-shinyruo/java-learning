package yier.bubu.concurrency.ratelimit;

import org.junit.Assert;
import org.junit.Test;

/**
 * 并发限流（Bulkhead / Concurrency limiting）- 测试即文档。
 * <p>
 * 它限制的是“同时在处理中的请求数”（in-flight），不是 QPS。
 * 使用方式：tryAcquire() 成功会返回 Permit，用完后 close() 释放名额。
 */
public class ConcurrencyLimiterTest {
    @Test
    public void concurrencyLimiter_shouldLimitInFlightRequests() {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(2);

        try (ConcurrencyLimiter.Permit p1 = limiter.tryAcquire();
             ConcurrencyLimiter.Permit p2 = limiter.tryAcquire()) {
            Assert.assertNotNull("第 1 个并发名额应成功获取", p1);
            Assert.assertNotNull("第 2 个并发名额应成功获取", p2);
            Assert.assertNull("超过 maxInFlight=2 后应返回 null（获取失败）", limiter.tryAcquire());
            Assert.assertEquals(2, limiter.getInFlight());
        }

        Assert.assertEquals("try-with-resources 结束后应自动释放", 0, limiter.getInFlight());
    }

    @Test
    public void permit_close_shouldBeIdempotent() {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(1);
        ConcurrencyLimiter.Permit p1 = limiter.tryAcquire();
        Assert.assertNotNull(p1);
        Assert.assertEquals(1, limiter.getInFlight());

        p1.close();
        p1.close();
        Assert.assertEquals(0, limiter.getInFlight());
    }
}
