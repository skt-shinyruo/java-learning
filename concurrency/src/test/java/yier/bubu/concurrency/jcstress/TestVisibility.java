package yier.bubu.concurrency.jcstress;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Mode;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.Signal;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * 一组 JCStress 用例：验证在 Java 内存模型（JMM）下，不同读写语义对“可见性 / 连贯性（coherence）/ 终止性”的影响。
 *
 * <p>JCStress 的使用方式：</p>
 * <ul>
 *   <li>每个 {@code Case} 都是一个独立的并发测试。</li>
 *   <li>{@link Actor} 方法会被框架并发执行（通常可理解为两个线程）。</li>
 *   <li>读线程把观测到的值写入结果对象（例如 {@link III_Result}），框架统计所有结果出现的次数与比例。</li>
 *   <li>{@link Outcome} 用来声明哪些结果是“可接受 / 有趣 / 禁止”。</li>
 * </ul>
 *
 * <p>{@link Outcome} 的 expect 字段含义：</p>
 * <ul>
 *   <li>{@link Expect#ACCEPTABLE}：合理可出现。</li>
 *   <li>{@link Expect#ACCEPTABLE_INTERESTING}：规范允许，但通常容易被误判、很适合做教学/复盘。</li>
 *   <li>{@link Expect#FORBIDDEN}：按该内存语义不应出现；出现说明你的假设不成立，或测试/平台存在问题。</li>
 * </ul>
 *
 * <p>注意：</p>
 * <ul>
 *   <li>Case1/Case4 是“故意写 data race 的反例”，结果具有平台/时序相关性；JCStress 的价值是把它变成统计结果。</li>
 *   <li>如果你想写“稳定必过”的单测，请使用 {@code volatile/synchronized/Atomic*} 等建立 happens-before 的手段，而不是依赖概率复现。</li>
 * </ul>
 */
public class TestVisibility {
    /**
     * Case1：测试对同一个普通变量的多次读操作是否“连贯”。
     *
     * <p>两个引用 {@code p} 与 {@code q} 指向同一个对象，但由于缺少同步手段，这里存在 data race：</p>
     * <ul>
     *   <li>{@code actor1} 并发读取 {@code x} 多次</li>
     *   <li>{@code actor2} 并发写入 {@code x = 3}</li>
     * </ul>
     *
     * <p>观察重点：{@code 0, 3, 0} 这种“读到新值又回到旧值”的结果是否可能出现。</p>
     *
     * <p>直觉上很多人会认为“同一个变量不会倒退”，但在 data race 下，JIT/CPU 允许很多优化与缓存行为，例如：</p>
     * <ul>
     *   <li>把某次读取缓存到寄存器，并在后续直接复用（等价于消除重复读取）</li>
     *   <li>由于别名分析不确定，{@code p.x} 与 {@code q.x} 可能被当作“不同位置的读取”对待</li>
     * </ul>
     *
     * <p>因此，这个 case 的目标不是证明“会/不会”，而是用 JCStress 在真实环境中把可能结果枚举出来。</p>
     */
    @JCStressTest
    @Outcome(id = {"3, 3, 3", "0, 0, 0"}, expect = Expect.ACCEPTABLE, desc = "ACCEPTABLE")
    @Outcome(id = {"0, 3, 3", "0, 0, 3"}, expect = Expect.ACCEPTABLE, desc = "ACCEPTABLE")
    @Outcome(id = "0, 3, 0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "INTERESTING")
    @State
    public static class Case1 {
        static class Foo {
            int x = 0;
        }

        Foo p = new Foo();
        Foo q = p;

        @Actor
        public void actor1(III_Result r) {
            r.r1 = p.x;
            r.r2 = q.x;
            r.r3 = p.x;
        }

        @Actor
        public void actor2() {
            p.x = 3;
        }
    }

    /**
     * Case2：测试对同一个 {@code volatile} 变量的多次读操作是否连贯。
     *
     * <p>相对 Case1，这里把 {@code x} 声明为 {@code volatile}，用于建立更强的可见性与重排序约束。</p>
     *
     * <p>观察重点：一旦某次读取已经看到了 {@code 3}，后续读取是否还可能看到 {@code 0}。</p>
     *
     * <p>这里把 {@code 0, 3, 0} 标为 {@link Expect#FORBIDDEN}：它代表“读到新值后又倒退到旧值”。</p>
     */
    @JCStressTest
    @Outcome(id = {"3, 3, 3", "0, 0, 0"}, expect = Expect.ACCEPTABLE, desc = "ACCEPTABLE")
    @Outcome(id = {"0, 3, 3", "0, 0, 3"}, expect = Expect.ACCEPTABLE_INTERESTING, desc = "INTERESTING")
    @Outcome(id = "0, 3, 0", expect = Expect.FORBIDDEN, desc = "FORBIDDEN")
    @State
    public static class Case2 {
        static class Foo {
            volatile int x = 0;
        }

        Foo p = new Foo();
        Foo q = p;

        @Actor
        public void actor1(III_Result r) {
            r.r1 = p.x;
            r.r2 = q.x;
            r.r3 = p.x;
        }

        @Actor
        public void actor2() {
            p.x = 3;
        }
    }

    /**
     * Case3：测试对同一个变量的多次读操作是否连贯（使用 VarHandle）。
     *
     * <p>这里保留 {@code int x} 的普通字段声明，但通过 {@link VarHandle} 的 {@code getOpaque/setOpaque} 进行访问。</p>
     *
     * <p>{@code Opaque} 的语义可以粗略理解为“比 plain 更强、比 volatile 更弱”。本 case 的假设是：</p>
     * <ul>
     *   <li>对同一个变量进行多次 {@code getOpaque} 读取，不应出现“读到新值后又倒退”的现象</li>
     * </ul>
     *
     * <p>如果你在某些平台/JVM 上真的观测到了 {@code 0, 3, 0}，可以把访问模式改为：</p>
     * <ul>
     *   <li>{@code getVolatile/setVolatile}（最强）</li>
     *   <li>或 {@code getAcquire/setRelease}（常用于停止标记/发布-订阅）</li>
     * </ul>
     */
    @JCStressTest
    @Outcome(id = {"3, 3, 3", "0, 0, 0"}, expect = Expect.ACCEPTABLE, desc = "ACCEPTABLE")
    @Outcome(id = {"0, 3, 3", "0, 0, 3"}, expect = Expect.ACCEPTABLE_INTERESTING, desc = "INTERESTING")
    @Outcome(id = "0, 3, 0", expect = Expect.FORBIDDEN, desc = "FORBIDDEN")
    @State
    public static class Case3 {
        static final VarHandle X;

        static {
            try {
                X = MethodHandles.lookup().findVarHandle(Foo.class, "x", int.class);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        static class Foo {
            int x = 0;
        }

        Foo p = new Foo();
        Foo q = p;

        @Actor
        public void actor1(III_Result r) {
            r.r1 = (int) X.getOpaque(p);
            r.r2 = (int) X.getOpaque(q);
            r.r3 = (int) X.getOpaque(p);
        }

        @Actor
        public void actor2() {
            X.setOpaque(p, 3);
        }
    }

    /**
     * Case4：测试停止标记（非 volatile）是否有效（终止性测试）。
     *
     * <p>经典反例：一个线程自旋 {@code while (!stop)}，另一个线程写 {@code stop=true}。</p>
     *
     * <p>在缺少同步（happens-before）时，读线程可能永远读不到新值，原因包括（但不限于）：</p>
     * <ul>
     *   <li>JIT 把读取 hoist 到循环外（等价于把循环变成无限循环）</li>
     *   <li>CPU 缓存/寄存器导致一直观察到旧值</li>
     * </ul>
     *
     * <p>因此 {@code STALE} 被标为 {@link Expect#ACCEPTABLE_INTERESTING}。</p>
     */
    @JCStressTest(value = Mode.Termination)
    @Outcome(id = {"TERMINATED"}, expect = Expect.ACCEPTABLE, desc = "ACCEPTABLE")
    @Outcome(id = {"STALE"}, expect = Expect.ACCEPTABLE_INTERESTING, desc = "INTERESTING")
    @State
    public static class Case4 {
        boolean stop;

        @Actor
        public void a1() {
            while (!stop) {
                // intentional empty spin
            }
        }

        @Signal
        void a2() {
            stop = true;
        }
    }

    /**
     * Case5：测试停止标记是否有效（使用 VarHandle）。
     *
     * <p>这里用 {@link VarHandle#getOpaque} / {@link VarHandle#setOpaque} 来读写停止标记。</p>
     *
     * <p>与 Case4 对比：我们期望读线程能及时观测到停止信号并退出，因此把非 TERMINATED 的结果标为 FORBIDDEN。</p>
     *
     * <p>提示：如果你的目标是“强保证”的停止标记（而不是实验 opaque 的效果），推荐直接使用：</p>
     * <ul>
     *   <li>{@code volatile boolean stop}</li>
     *   <li>或 VarHandle 的 {@code getAcquire/setRelease} / {@code getVolatile/setVolatile}</li>
     * </ul>
     */
    @JCStressTest(value = Mode.Termination)
    @Outcome(id = {"TERMINATED"}, expect = Expect.ACCEPTABLE, desc = "ACCEPTABLE")
    @Outcome(expect = Expect.FORBIDDEN, desc = "FORBIDDEN")
    @State
    public static class Case5 {
        boolean stop;
        static final VarHandle STOP;

        static {
            try {
                STOP = MethodHandles.lookup().findVarHandle(Case5.class, "stop", boolean.class);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        @Actor
        public void a1() {
            while (true) {
                if (((boolean) STOP.getOpaque(this))) {
                    break;
                }
            }
        }

        @Signal
        void a2() {
            STOP.setOpaque(this, true);
        }
    }
}
