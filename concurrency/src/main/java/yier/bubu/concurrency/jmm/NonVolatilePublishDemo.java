package yier.bubu.concurrency.jmm;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 非 volatile 的 publish 反例演示。
 *
 * 说明：
 * - 没有 volatile/happens-before 约束时，读线程可能出现：
 *   - 看到 ready==true
 *   - 但读到 value 仍是旧值
 * - 这是概率性现象，不保证稳定复现。
 */
public final class NonVolatilePublishDemo {
    private int value;
    private boolean ready;

    public boolean tryRunOnceAndValidate(long timeout, TimeUnit unit, int expected) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        int[] observed = new int[1];

        Thread reader = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            while (!ready) {
                // spin
            }
            observed[0] = value;
        }, "nonvolatile-publish-reader");

        Thread writer = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            value = expected;
            ready = true;
        }, "nonvolatile-publish-writer");

        reader.start();
        writer.start();
        start.countDown();

        reader.join(unit.toMillis(timeout));
        writer.join(unit.toMillis(timeout));

        // 如果线程没结束，说明可能卡住（也是非 volatile 的一种“坏现象”），此处返回 true 让上层继续尝试/结束。
        if (reader.isAlive() || writer.isAlive()) {
            return true;
        }
        return observed[0] == expected;
    }
}

