package yier.bubu.concurrency.jcstress;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * 一组 JCStress 用例：验证“部分有序性（partial ordering）”以及 volatile 放置位置的真实效果。
 *
 * <p>测试结构保持不变：</p>
 * <ul>
 *   <li>写线程：按程序顺序写 {@code x}、{@code y}</li>
 *   <li>读线程：先读 {@code y}，再读 {@code x}</li>
 *   <li>结果 {@code r1, r2} 对应“读到的 {@code y, x}”</li>
 * </ul>
 *
 * <p>最值得观察的结果是 {@code 1, 0}：它表示读线程看到了较晚写入的 {@code y=1}，却没有看到较早写入的
 * {@code x=1}。这通常说明当前写法没有建立起你以为存在的 happens-before 关系。</p>
 *
 * <p>这组用例的教学重点不是“volatile 很神奇”，而是：</p>
 * <ul>
 *   <li>volatile 只会在“同一个 volatile 变量的写-读配对”上建立 synchronizes-with / happens-before。</li>
 *   <li>volatile 写只对它之前的普通操作提供 release 效果。</li>
 *   <li>volatile 读只对它之后的普通操作提供 acquire 效果。</li>
 *   <li>把 volatile 放到“错误的变量”或“错误的顺序位置”上，不能替代正确的发布-读取链路。</li>
 * </ul>
 */
public class TestOrderingPartial {
    /**
     * Case1：两个字段都是普通字段。
     *
     * <p>这里没有任何同步动作，完全属于 data race。{@code 1, 0} 因而是规范允许的，只是很反直觉：</p>
     * <ul>
     *   <li>写线程顺序：{@code x = 1; y = 1;}</li>
     *   <li>读线程顺序：{@code r1 = y; r2 = x;}</li>
     *   <li>如果读线程先观察到 {@code y=1}，并不意味着它也必须观察到更早的 {@code x=1}</li>
     * </ul>
     */
    @JCStressTest
    @Outcome(id = {"0, 0", "1, 1", "0, 1"}, expect = Expect.ACCEPTABLE, desc = "ACCEPTABLE")
    @Outcome(id = "1, 0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "INTERESTING")
    @State
    public static class Case1 {
        int x;
        int y;

        @Actor
        public void actor1() {
            x = 1;
            y = 1;
        }

        @Actor
        public void actor2(II_Result r) {
            r.r1 = y;
            r.r2 = x;
        }
    }

    /**
     * Case2：把 {@code y} 声明为 volatile，构造标准的“数据 + 发布标记”模型。
     *
     * <p>这里的关键链路是：</p>
     * <ul>
     *   <li>写线程先做普通写 {@code x=1}</li>
     *   <li>再做 volatile 写 {@code y=1}</li>
     *   <li>读线程先做 volatile 读 {@code y}</li>
     *   <li>再做普通读 {@code x}</li>
     * </ul>
     *
     * <p>如果读线程观察到了 {@code y=1}，那这次 volatile 读就与写线程的 volatile 写配对，建立 happens-before。
     * 因此写线程在此之前对 {@code x} 的写入也必须对读线程可见，{@code 1, 0} 应当被禁止。</p>
     */
    @JCStressTest
    @Outcome(id = {"0, 0", "1, 1", "0, 1"}, expect = Expect.ACCEPTABLE, desc = "ACCEPTABLE")
    @Outcome(id = "1, 0", expect = Expect.FORBIDDEN, desc = "FORBIDDEN")
    @State
    public static class Case2 {
        int x;
        volatile int y;

        @Actor
        public void actor1() {
            x = 1;
            y = 1;
        }

        @Actor
        public void actor2(II_Result r) {
            r.r1 = y;
            r.r2 = x;
        }
    }

    /**
     * Case3：把 {@code x} 声明为 volatile，看上去更“强”，但其实 volatile 放错了位置。
     *
     * <p>很多人第一次看到这个 case 会误以为 {@code 1, 0} 应该和 Case2 一样被禁止，但实际上不对。</p>
     *
     * <p>原因在于这里没有形成正确的发布-读取链路：</p>
     * <ul>
     *   <li>写线程的 volatile 写是 {@code x=1}</li>
     *   <li>普通写 {@code y=1} 发生在 volatile 写之后，所以它不是“被该 volatile 发布出去的数据”</li>
     *   <li>读线程先读普通字段 {@code y}，再读 volatile 字段 {@code x}</li>
     *   <li>普通读 {@code y} 发生在 volatile 读之前，所以它也拿不到该 volatile 读的 acquire 保护</li>
     * </ul>
     *
     * <p>换句话说：Case2 的 volatile 在“发布标记”位置上，而这里的 volatile 在“数据字段”位置上。它们不是等价写法。
     * 因此 {@code 1, 0} 仍然是允许的，适合标记为 {@link Expect#ACCEPTABLE_INTERESTING}。</p>
     */
    @JCStressTest
    @Outcome(id = {"0, 0", "1, 1", "0, 1"}, expect = Expect.ACCEPTABLE, desc = "ACCEPTABLE")
    @Outcome(id = "1, 0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "INTERESTING")
    @State
    public static class Case3 {
        volatile int x;
        int y;

        @Actor
        public void actor1() {
            x = 1;
            y = 1;
        }

        @Actor
        public void actor2(II_Result r) {
            r.r1 = y;
            r.r2 = x;
        }
    }
}
