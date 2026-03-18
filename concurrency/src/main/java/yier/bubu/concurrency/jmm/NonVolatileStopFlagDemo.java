package yier.bubu.concurrency.jmm;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 非 volatile 的 stop flag 反例演示。
 *
 * 说明：
 * - 这类问题通常是“可能发生”，不保证稳定复现。
 * - 在某些 CPU/JIT/负载下，子线程可能长期读到旧值，从而无法退出循环。
 */
public final class NonVolatileStopFlagDemo {
    private final CountDownLatch started = new CountDownLatch(1);
    private boolean running = true;

    @SuppressWarnings("unused")
    private int sink;

    public void run() {
        started.countDown();
        int local = 0;
        while (running) {
            local = (local * 1664525) + 1013904223;
        }
        sink = local;
    }

    public boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
        return started.await(timeout, unit);
    }

    public void stop() {
        running = false;
    }
}

