package yier.bubu.concurrency.jmm;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * volatile 可见性演示：使用 volatile 标记 stop flag（running）。
 *
 * 典型现象：
 * - 子线程在 while(running) 中自旋
 * - 主线程把 running=false
 * - running 是 volatile 时，子线程应当能及时观察到变化并退出
 */
public final class VolatileStopFlagDemo {
    private final CountDownLatch started = new CountDownLatch(1);
    private volatile boolean running = true;
    private int iterations;

    public void run() {
        started.countDown();
        int local = 0;
        while (running) {
            // 做一点无意义的计算，避免空循环被过度优化（demo 目的）
            local = (local * 1664525) + 1013904223;
        }
        iterations = (local == 0) ? 1 : Math.abs(local);
    }

    public boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
        return started.await(timeout, unit);
    }

    public void stop() {
        running = false;
    }

    public int iterations() {
        return iterations;
    }
}

