# JL-03 线程、锁与 JMM Spec

父级规格：[JVM 实战靶场总 Spec](./jvm-lab-master-spec.md)

## 1. 目标

建设线程、锁和 Java 内存模型实验，把 `volatile`、`synchronized`、CAS、锁竞争、线程状态和死锁排查与真实 JVM 现象关联起来。

## 2. 范围

本 spec 覆盖：

- `volatile` 可见性。
- 复合操作非原子性。
- 死锁制造与 `jstack` 定位。
- 线程阻塞和线程状态观察。
- `synchronized`、`ReentrantLock`、`AtomicInteger`、`LongAdder` 的性能比较。

并发理论文档已经较多地分布在 `concurrency/docs/`，JVM 靶场文档应链接这些资料，重点放在“运行现象和排查证据”。

## 3. 实验清单

| 实验编号 | 名称 | 交付重点 |
| --- | --- | --- |
| JL-03-LAB-01 | volatile 可见性 | 非 volatile 停止标记、JIT 优化、`volatile` 内存语义 |
| JL-03-LAB-02 | 复合操作非原子性 | `count++`、AtomicInteger、LongAdder 对比 |
| JL-03-LAB-03 | 死锁排查 | `jps`、`jstack`、持有锁、等待锁、代码位置 |
| JL-03-LAB-04 | 线程阻塞观察 | `BLOCKED`、`WAITING`、`TIMED_WAITING`、线程栈证据 |
| JL-03-LAB-05 | 锁性能 JMH | 单线程、多线程、高竞争、低竞争 |

## 4. 代码设计约束

- 包名建议使用 `yier.bubu.jvm.jmm` 和 `yier.bubu.jvm.threading`。
- 可能不稳定的并发现象不能作为普通单元测试的硬断言。
- 死锁实验必须手动运行，普通测试不启动永久死锁线程。
- 锁性能结论必须通过 JMH，不用手写 `System.nanoTime()` 做最终判断。
- 若 JMH 依赖影响普通 Maven 构建，应通过 profile 或独立 benchmark 源集隔离。

## 5. 文档设计

建议新增：

- `jvm/docs/labs/jl-03-thread-lock-jmm-lab.md`
- `jvm/docs/runbooks/jl-03-deadlock-jstack-runbook.md`
- `jvm/docs/reports/jl-03-deadlock-report-template.md`

死锁 Runbook 至少包含：

- 如何启动死锁程序。
- 如何通过 `jps` 找到 PID。
- 如何执行 `jstack <pid>`。
- 如何识别 `Found one Java-level deadlock`。
- 如何从线程栈定位持有锁和等待锁的代码行。

## 6. 验收标准

- `mvn -pl jvm -am test` 通过。
- 死锁和阻塞实验有明确退出方式或使用手动终止说明。
- 文档区分 JMM 概念解释和线程栈中的实际证据。
- JMH 实验给出运行命令、预热参数、测量参数和解释限制。
- 与 `concurrency/docs/` 中已有 JMM、CAS、synchronized、ThreadLocal 等资料建立交叉链接。

## 7. 非目标

- 不用概率性并发结果作为稳定单元测试。
- 不在 JVM 模块重复搬运所有 `concurrency` 模块理论内容。
- 不实现完整线程池故障平台，线程池问题可在 JL-05 中作为故障场景扩展。
