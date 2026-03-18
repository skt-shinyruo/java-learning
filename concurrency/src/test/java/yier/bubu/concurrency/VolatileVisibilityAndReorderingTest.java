package yier.bubu.concurrency;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import yier.bubu.concurrency.jmm.NonVolatilePublishDemo;
import yier.bubu.concurrency.jmm.NonVolatileStopFlagDemo;
import yier.bubu.concurrency.jmm.VolatilePublishDemo;
import yier.bubu.concurrency.jmm.VolatileStopFlagDemo;

import java.util.concurrent.TimeUnit;

/**
 * 这个测试类专门用于“用代码示例说明” volatile 在 Java 内存模型（JMM）下的两个关键作用：
 *
 * <p>1) 可见性：一个线程对 volatile 变量的写入，另一个线程能及时读到最新值。</p>
 * <p>2) 有序性：volatile 写/读在内存语义上分别具备 release/acquire 效果，
 * 通过 happens-before 关系，禁止某些关键的指令重排，并保证相关普通变量的写入对读线程可见。</p>
 *
 * <p>配套文档：concurrency/docs/volatile-jmm.md</p>
 *
 * 注意：
 * - 下面的“正确示例”（带 volatile）应当稳定通过。
 * - “反例示范”（不带 volatile）属于概率复现问题，默认用 @Ignore 跳过，避免在不同机器/JIT/负载下变成不稳定测试。
 */
public class VolatileVisibilityAndReorderingTest {

    /**
     * 测试目的：证明 volatile 能解决“可见性”问题。
     *
     * <p>测试方案：</p>
     * <ul>
     *   <li>启动一个工作线程，在 {@link VolatileStopFlagDemo} 里自旋执行 {@code while (running)}。</li>
     *   <li>主线程在工作线程启动后，将 {@code running=false}。</li>
     *   <li>通过 {@code join + timeout} 断言工作线程能在限定时间内结束。</li>
     * </ul>
     *
     * <p>结论（应当稳定成立）：</p>
     * <ul>
     *   <li>{@code running} 为 volatile 时，主线程对 {@code running=false} 的写入对工作线程可见，工作线程应当退出循环。</li>
     * </ul>
     *
     * <p>知识点：</p>
     * <ul>
     *   <li>JMM 可见性：volatile 写会把新值刷新为对其他线程可见；volatile 读会从“对其他线程可见的位置”重新读取。</li>
     *   <li>空循环与优化：demo 里做了少量计算，是为了降低空自旋被过度优化的概率（不是证明点本身）。</li>
     * </ul>
     */
    @Test(timeout = 2000)
    public void volatile_stopFlag_shouldBeVisibleToSpinThread() throws Exception {
        VolatileStopFlagDemo demo = new VolatileStopFlagDemo();

        Thread worker = new Thread(demo::run, "volatile-stop-flag-worker");
        worker.start();

        Assert.assertTrue("worker should start", demo.awaitStarted(1, TimeUnit.SECONDS));
        demo.stop();

        worker.join(TimeUnit.SECONDS.toMillis(1));
        Assert.assertFalse("worker should stop after flag update", worker.isAlive());
        Assert.assertTrue("worker should have looped at least once", demo.iterations() > 0);
    }

    /**
     * 测试目的：证明 volatile 能通过 happens-before 关系提供“有序性/发布（publish）保证”，从而禁止关键的指令重排并保证可见性。
     *
     * <p>测试方案（典型 publish/consume 模式）：</p>
     * <ul>
     *   <li>写线程每一轮先写普通变量 {@code value=i}，再写 volatile 变量 {@code ready=i}。</li>
     *   <li>读线程自旋等待读到 {@code ready==i}，然后读取普通变量 {@code value} 并断言 {@code value==i}。</li>
     *   <li>使用 {@link VolatilePublishDemo} 内部的 {@code ack(volatile)} 做握手，保证每一轮都能推进并逐轮验证。</li>
     * </ul>
     *
     * <p>结论（应当稳定成立）：</p>
     * <ul>
     *   <li>读线程一旦观察到 {@code ready==i}，就必须观察到本轮写线程在此之前写入的 {@code value==i}。</li>
     * </ul>
     *
     * <p>知识点与解释：</p>
     * <ul>
     *   <li>happens-before：对同一个 volatile 变量的写入，与另一个线程后续读到该值之间形成 happens-before。</li>
     *   <li>release/acquire 语义：volatile 写具备 release 效果，volatile 读具备 acquire 效果。</li>
     *   <li>重排限制：普通写不能被重排到 volatile 写之后；普通读不能被重排到 volatile 读之前。</li>
     *   <li>综合效果：看到 volatile 的“发布标记”后，读线程必须看到发布前的普通写入结果。</li>
     * </ul>
     */
    @Test(timeout = 3000)
    public void volatile_readyFlag_shouldPublishPriorWrites_noReorderingAcrossVolatile() throws Exception {
        VolatilePublishDemo demo = new VolatilePublishDemo(200_000);
        demo.runAndAssert(2, TimeUnit.SECONDS);
    }

    /**
     * 反例示范（默认跳过）：不加 volatile 时，stop flag 可能出现“不可见”。
     *
     * <p>测试目的：</p>
     * <ul>
     *   <li>说明：如果没有 volatile/synchronized/锁等同步手段，JMM 不保证一个线程写入的值能被另一个线程及时看见。</li>
     * </ul>
     *
     * <p>为什么默认跳过：</p>
     * <ul>
     *   <li>这是概率性现象：是否复现依赖 CPU 缓存、JIT 优化、运行时负载等，不能作为稳定单测。</li>
     * </ul>
     */
    @Ignore("反例示范：不加 volatile 时，子线程可能一直观察不到 stop 标记（该现象可能随 CPU/JIT/负载不同而不复现）。")
    @Test(timeout = 2000)
    public void nonVolatile_stopFlag_mayNotBeVisibleToSpinThread() throws Exception {
        NonVolatileStopFlagDemo demo = new NonVolatileStopFlagDemo();

        Thread worker = new Thread(demo::run, "nonvolatile-stop-flag-worker");
        worker.start();

        Assert.assertTrue("worker should start", demo.awaitStarted(1, TimeUnit.SECONDS));
        demo.stop();

        worker.join(TimeUnit.SECONDS.toMillis(1));
        Assert.assertFalse("worker may not stop without volatile (expected to be flaky)", worker.isAlive());
    }

    /**
     * 反例示范（默认跳过）：不加 volatile/happens-before 时，publish/consume 模式可能出现“读到标记但读不到数据”的情况。
     *
     * <p>测试目的：</p>
     * <ul>
     *   <li>说明：没有 happens-before 约束时，读线程可能观察到 {@code ready==true}，但 {@code value} 仍然是旧值。</li>
     * </ul>
     *
     * <p>为什么默认跳过：</p>
     * <ul>
     *   <li>同样属于概率性现象，不保证稳定复现；作为示范更合适，而不是稳定单测。</li>
     * </ul>
     */
    @Ignore("反例示范：不加 volatile/happens-before 时，可能出现 ready 可见但 value 仍是旧值（概率性，不保证稳定复现）。")
    @Test(timeout = 5000)
    public void nonVolatile_readyFlag_mayAllowReorderingOrStaleReads() throws Exception {
        for (int i = 0; i < 2_000_000; i++) {
            NonVolatilePublishDemo demo = new NonVolatilePublishDemo();
            if (!demo.tryRunOnceAndValidate(1, TimeUnit.SECONDS, 42)) {
                return;
            }
        }
        Assert.fail("未复现（属于概率现象）；可尝试加大循环次数，或在不同 CPU/JIT 条件下运行（例如 -Xcomp）。");
    }
}
