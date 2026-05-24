# JL-04 JIT 与性能优化 Spec

父级规格：[JVM 实战靶场总 Spec](./jvm-lab-master-spec.md)

## 1. 目标

建设 JIT 和性能优化实验，让学习者理解代码为什么会变快、微基准为什么容易错，以及如何用 JMH 观察预热、内联、死代码消除、常量折叠、逃逸分析和 GC 干扰。

## 2. 范围

本 spec 覆盖：

- JMH 入门。
- for 循环与 Stream 的微基准比较。
- 死代码消除和 Blackhole。
- 常量折叠。
- 方法内联观察。
- 逃逸分析、标量替换和锁消除。
- 开启或关闭逃逸分析的对比。

## 3. 实验清单

| 实验编号 | 名称 | 交付重点 |
| --- | --- | --- |
| JL-04-LAB-01 | JMH 入门 | benchmark 结构、warmup、measurement、fork |
| JL-04-LAB-02 | 循环与 Stream 对比 | 数据规模、装箱影响、JIT 预热 |
| JL-04-LAB-03 | 死代码消除 | 错误 benchmark 与 Blackhole 修正 |
| JL-04-LAB-04 | 逃逸分析 | `-XX:+DoEscapeAnalysis`、`-XX:-DoEscapeAnalysis`、对象分配变化 |
| JL-04-LAB-05 | 锁消除和标量替换 | 对象是否一定分配在堆上、同步是否一定保留 |

## 4. 代码设计约束

- 包名建议使用 `yier.bubu.jvm.jit`。
- JMH 代码不要混入普通 JUnit 测试目录导致 Surefire 误跑。
- 若项目继续保持 Java 8，JMH 版本需兼容 Java 8。
- benchmark 参数必须写在文档里，避免只给 IDE 运行方式。
- 任何性能结论都必须说明硬件、JDK 版本、JVM 参数和 benchmark 参数。

## 5. 文档设计

建议新增：

- `jvm/docs/labs/jl-04-jit-jmh-lab.md`
- `jvm/docs/reports/jl-04-jmh-result-report-template.md`

每个 benchmark 复盘至少记录：

- JDK 版本。
- 操作系统和 CPU 简要信息。
- JVM 参数。
- JMH warmup、measurement、fork 配置。
- 样本输出。
- 结论边界。

## 6. 验收标准

- 普通 `mvn -pl jvm -am test` 不执行长时间 benchmark。
- 有单独命令运行 JMH，例如 Maven profile 或 benchmark jar。
- 至少包含一个“错误 benchmark”和一个“修正后 benchmark”的对照。
- 逃逸分析实验提供开启和关闭参数，并说明不同 JDK 可能有差异。
- 文档明确提醒：微基准结果不能直接等同于真实业务性能。

## 7. 非目标

- 不建设完整压测平台。
- 不把 JMH 结果作为跨机器固定数值断言。
- 不用手写计时器替代 JMH。
