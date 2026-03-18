package yier.bubu.concurrency.jmm;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

/**
 * 这里相当于原来 main 的“测试版入口”：
 * - 不做打印
 * - 只验证 demo 能在合理时间内跑完，并满足预期断言
 *
 * 说明：真正解释 volatile 语义的测试在 {@link yier.bubu.concurrency.VolatileVisibilityAndReorderingTest}。
 */
public class VolatileDemosSmokeTest {

    @Test(timeout = 2000)
    public void volatileStopFlagDemo_shouldStop() throws Exception {
        VolatileStopFlagDemo demo = new VolatileStopFlagDemo();
        Thread worker = new Thread(demo::run, "volatile-stop-flag-worker");
        worker.start();

        Assert.assertTrue("线程应该启动", demo.awaitStarted(1, TimeUnit.SECONDS));
        demo.stop();

        worker.join(TimeUnit.SECONDS.toMillis(1));
        Assert.assertFalse("线程应当在 stop 后退出", worker.isAlive());
    }

    @Test(timeout = 3000)
    public void volatilePublishDemo_shouldAlwaysSeeValueAfterReady() throws Exception {
        VolatilePublishDemo demo = new VolatilePublishDemo(200_000);
        demo.runAndAssert(2, TimeUnit.SECONDS);
    }
}

