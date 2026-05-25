# JL-05 线上故障排查 Spec

父级规格：[JVM 实战靶场总 Spec](./jvm-lab-master-spec.md)

## 1. 目标

建设一个小型故障模拟靶场，提供接近线上排查的场景：高 CPU、内存泄漏、线程死锁、线程阻塞、频繁 GC、直接内存 OOM。目标是训练从系统现象到 JVM 证据再到代码根因的完整链路。

## 2. 范围

本 spec 覆盖：

- 普通 Java CLI 故障入口，优先保持轻量。
- 可选 HTTP 服务入口，后续需要时再引入。
- 高 CPU 定位流程：`top`、`top -H -p`、线程 ID 转十六进制、`jstack`。
- 内存泄漏定位流程：`jcmd GC.heap_info`、`GC.class_histogram`、heap dump、MAT。
- 频繁 Full GC 定位流程：`jstat -gcutil`、`VM.flags`、GC 日志。
- 线程阻塞和死锁定位流程。

## 3. 场景清单

| 场景编号 | 名称 | 交付重点 |
| --- | --- | --- |
| JL-05-LAB-01 | 高 CPU | 定位到具体线程和代码循环点 |
| JL-05-LAB-02 | 静态集合内存泄漏 | class histogram、heap dump、GC Roots |
| JL-05-LAB-03 | ThreadLocal 泄漏 | 线程生命周期、ThreadLocalMap、清理策略 |
| JL-05-LAB-04 | 死锁 | `jstack` 死锁报告和锁依赖 |
| JL-05-LAB-05 | 线程阻塞 | 锁竞争、等待队列、线程状态 |
| JL-05-LAB-06 | 频繁 GC | 分配速率、晋升、老年代上涨、堆大小 |
| JL-05-LAB-07 | Direct Memory OOM | 堆外内存、NIO 场景、Netty 关联解释 |

## 4. 代码设计约束

- 包名建议使用 `yier.bubu.jvm.troubleshooting`。
- 第 1 批优先用 CLI 子命令实现，不强制引入 Spring Boot。
- 如果后续引入 HTTP 服务，应先评估是否破坏 Java 8 和轻量学习仓库定位。
- 每个故障场景必须支持可调参数，例如运行秒数、分配大小、线程数量。
- 长时间运行和危险场景必须由用户手动启动，普通测试不触发。

## 5. Runbook 设计

建议新增：

- `jvm/docs/runbooks/jl-05-high-cpu-runbook.md`
- `jvm/docs/runbooks/jl-05-memory-leak-runbook.md`
- `jvm/docs/runbooks/jl-05-full-gc-runbook.md`
- `jvm/docs/runbooks/jl-05-thread-block-runbook.md`

高 CPU Runbook 必须包含：

```bash
top
top -H -p <pid>
printf "%x\n" <tid>
jstack <pid> > thread.txt
```

并说明如何在线程栈中搜索十六进制 `nid`。

## 6. 复盘模板

每个故障场景复盘至少回答：

- 用户侧现象是什么。
- OS 层指标是什么。
- JVM 层证据是什么。
- 代码位置在哪里。
- 根因属于死循环、锁竞争、缓存无界、ThreadLocal 未清理、队列积压、堆太小还是分配速率过高。
- 修复方案是什么。
- 修复后如何验证。

## 7. 验收标准

- CLI 入口能列出所有故障场景和危险提示。
- 高 CPU 场景能稳定定位到一个命名线程。
- 内存泄漏场景能通过 `jcmd GC.class_histogram` 看到目标对象数量上升。
- 频繁 GC 场景能生成可分析的 GC 日志。
- 每个 Runbook 命令都能从仓库根目录或明确的运行环境执行。
- 文档明确说明 Linux/macOS/Windows 工具差异；第 1 批以 Linux 命令为主。

## 8. 非目标

- 不在第 1 批引入完整 Web 平台。
- 不模拟所有线上故障类型。
- 不提交大型运行产物。
