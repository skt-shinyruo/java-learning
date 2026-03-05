package yier.bubu.concurrency.ratelimit;

import org.junit.Assert;
import org.junit.Test;

/**
 * 队列/排队限流（Bounded Queue）- 测试即文档。
 * <p>
 * 它的逻辑非常直接：
 * - 允许入队直到容量上限
 * - 队列满则拒绝（相当于把压力挡在入口）
 * - 出队会释放空间
 */
public class BoundedQueueLimiterTest {
    @Test
    public void boundedQueue_shouldAcceptUntilCapacity_thenReject() {
        BoundedQueueLimiter limiter = new BoundedQueueLimiter(2);

        Assert.assertTrue("第 1 次入队应成功", limiter.tryEnqueue());
        Assert.assertTrue("第 2 次入队应成功（达到 capacity）", limiter.tryEnqueue());
        Assert.assertFalse("队列满后入队应失败", limiter.tryEnqueue());
        Assert.assertEquals(2, limiter.getSize());
    }

    @Test
    public void dequeue_shouldFreeSpace_forNewEnqueue() {
        BoundedQueueLimiter limiter = new BoundedQueueLimiter(2);
        Assert.assertTrue(limiter.tryEnqueue());
        Assert.assertTrue(limiter.tryEnqueue());
        Assert.assertFalse(limiter.tryEnqueue());

        Assert.assertTrue(limiter.tryDequeue());
        Assert.assertEquals(1, limiter.getSize());
        Assert.assertTrue("出队后应有空位，入队应成功", limiter.tryEnqueue());
        Assert.assertEquals(2, limiter.getSize());
    }
}
