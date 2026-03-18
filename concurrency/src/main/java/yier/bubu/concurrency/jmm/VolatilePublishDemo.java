package yier.bubu.concurrency.jmm;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * volatile 有序性/发布（publish）演示：通过 volatile 建立 happens-before。
 *
 * 模型：
 * - 写线程：value=i; ready=i(volatile)
 * - 读线程：读到 ready==i 后，再读 value
 *
 * 期望：
 * - 读线程一旦观察到 ready==i，就必须观察到 value==i
 *
 * 这里用 ack(volatile) 做握手，保证每一轮都能推进，读线程也能逐轮验证。
 */
public final class VolatilePublishDemo {
    private final int iterations;
    private volatile int ready;
    private volatile int ack;
    private int value;

    public VolatilePublishDemo(int iterations) {
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be positive");
        }
        this.iterations = iterations;
    }

    public void runAndAssert(long timeout, TimeUnit unit) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<AssertionError> error = new AtomicReference<>();

        Thread reader = new Thread(() -> {
            try {
                start.await();
                for (int i = 1; i <= iterations; i++) {
                    while (ready != i) {
                        // spin
                    }
                    int observed = value;
                    if (observed != i) {
                        error.compareAndSet(null, new AssertionError("看到 ready==" + i + " 后 value 仍然不是 " + i + "，实际是 " + observed));
                        ack = i;
                        return;
                    }
                    ack = i;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        }, "volatile-publish-reader");

        Thread writer = new Thread(() -> {
            try {
                start.await();
                for (int i = 1; i <= iterations; i++) {
                    if (error.get() != null) {
                        return;
                    }
                    value = i;
                    ready = i;
                    while (ack != i) {
                        // spin
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        }, "volatile-publish-writer");

        reader.start();
        writer.start();
        start.countDown();

        boolean finished = done.await(timeout, unit);
        if (!finished) {
            throw new IllegalStateException("超时：线程未在指定时间内结束");
        }
        if (error.get() != null) {
            throw error.get();
        }
    }
}

