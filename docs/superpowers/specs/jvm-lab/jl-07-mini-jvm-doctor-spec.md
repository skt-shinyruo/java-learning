# JL-07 Mini JVM Doctor Spec

父级规格：[JVM 实战靶场总 Spec](./jvm-lab-master-spec.md)

## 1. 目标

在故障实验和 Agent 实验完成后，建设一个轻量 JVM 诊断小工具 Mini JVM Doctor，把常见 JVM 诊断动作封装成可运行命令，并生成中文诊断报告。

## 2. 范围

本 spec 覆盖：

- 查看当前 JVM 参数。
- 查看堆内存使用情况。
- 查看线程状态。
- 检测死锁。
- 打印类加载数量。
- 触发 heap dump。
- 开启 JFR 录制，限 JDK 11+ 或明确标注版本。
- 统计接口或方法耗时，复用 JL-06 Agent 能力。
- 检测慢方法。
- 生成诊断报告。

## 3. 功能清单

| 功能编号 | 名称 | 交付重点 |
| --- | --- | --- |
| JL-07-FEAT-01 | JVM 参数查看 | RuntimeMXBean、输入参数、系统属性摘要 |
| JL-07-FEAT-02 | 堆内存查看 | MemoryMXBean、MemoryPoolMXBean |
| JL-07-FEAT-03 | 线程状态查看 | ThreadMXBean、线程状态统计 |
| JL-07-FEAT-04 | 死锁检测 | `findDeadlockedThreads()` |
| JL-07-FEAT-05 | 类加载统计 | ClassLoadingMXBean |
| JL-07-FEAT-06 | heap dump | HotSpotDiagnosticMXBean |
| JL-07-FEAT-07 | JFR 录制 | JDK 11+ 扩展，或外部 `jcmd` Runbook |
| JL-07-FEAT-08 | 慢方法统计 | 复用 Agent 输出或内置计时器 |
| JL-07-FEAT-09 | 诊断报告 | Markdown 报告，包含证据和建议 |

## 4. 代码设计约束

- 包名建议使用 `yier.bubu.jvm.doctor`。
- 第一个版本优先诊断当前 JVM 进程，不做跨进程 attach。
- 涉及 HotSpot 私有 MBean 的能力必须优雅降级。
- 报告输出到 `target/jvm-doctor/`，不提交生成报告。
- JFR 能力要明确区分 Java 8 和 JDK 11+。Java 8 下可提供不可用提示或外部命令 Runbook。

## 5. CLI 设计

建议命令形态：

```bash
java -cp jvm/target/classes yier.bubu.jvm.doctor.MiniJvmDoctor summary
java -cp jvm/target/classes yier.bubu.jvm.doctor.MiniJvmDoctor threads
java -cp jvm/target/classes yier.bubu.jvm.doctor.MiniJvmDoctor deadlock
java -cp jvm/target/classes yier.bubu.jvm.doctor.MiniJvmDoctor heap-dump --file target/jvm-doctor/heap.hprof
java -cp jvm/target/classes yier.bubu.jvm.doctor.MiniJvmDoctor report --file target/jvm-doctor/report.md
```

也可以后续整合进统一 `JvmLabApp`，但不能让入口类变成难以维护的巨型分发器。

## 6. 文档设计

建议新增：

- `jvm/docs/labs/jl-07-mini-jvm-doctor-lab.md`
- `jvm/docs/runbooks/jl-07-diagnostic-report-runbook.md`
- `jvm/docs/reports/jl-07-diagnostic-report-template.md`

报告模板至少包含：

- 基本信息。
- JVM 参数。
- 堆和非堆内存。
- 线程状态。
- 死锁检测结果。
- 类加载数量。
- 可疑项。
- 建议动作。

## 7. 验收标准

- `summary`、`threads`、`deadlock` 能在普通 Java 8 环境运行。
- heap dump 命令能在 HotSpot 下生成文件，并提示文件不要提交。
- 报告命令生成 Markdown。
- 当前 JVM 诊断不需要外部 PID。
- JFR 功能在不支持的 JDK 上给出清晰提示。
- 工具文档引用 JL-02、JL-05、JL-06 的实验和 Runbook。

## 8. 非目标

- 不实现完整 Arthas 替代品。
- 不做远程诊断。
- 不做跨平台系统指标采集。
- 不把报告建议伪装成自动调优结论。
