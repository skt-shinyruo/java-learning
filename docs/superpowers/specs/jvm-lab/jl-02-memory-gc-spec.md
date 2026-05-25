# JL-02 内存模型与 GC Spec

父级规格：[JVM 实战靶场总 Spec](./jvm-lab-master-spec.md)

## 1. 目标

建设内存和 GC 实验，覆盖堆、直接内存、元空间、线程栈、GC 日志和常见 OOM 定位流程。目标是能制造问题、拿到证据、解释对象为什么没有被回收，并能给出参数或代码层面的优化方案。

## 2. 范围

本 spec 覆盖：

- Heap OOM。
- Direct Memory OOM。
- Metaspace OOM。
- StackOverflowError。
- GC pressure 与 GC 日志分析。
- `jcmd`、`jmap`、`jstat`、MAT、VisualVM 的观察流程。

当前 `jvm` 模块已有 `DirectMemoryDemo`、`MetaspaceDemo`、`StackOverflowDemo` 和 `MemoryInspector`，后续应优先复用或迁移到清晰包结构。

## 3. 实验清单

| 实验编号 | 名称 | 交付重点 |
| --- | --- | --- |
| JL-02-LAB-01 | Heap OOM | `-Xms`、`-Xmx`、heap dump、GC Roots、最大对象 |
| JL-02-LAB-02 | Direct Memory OOM | `ByteBuffer.allocateDirect()`、`MaxDirectMemorySize`、堆外内存证据 |
| JL-02-LAB-03 | Metaspace OOM | 动态定义大量类、`MaxMetaspaceSize`、ClassLoader 持有关系 |
| JL-02-LAB-04 | StackOverflowError | `-Xss`、递归深度、线程栈大小 |
| JL-02-LAB-05 | GC 日志分析 | G1 日志、Young GC、Full GC、晋升、分配速率 |

## 4. 代码设计约束

- 包名建议使用 `yier.bubu.jvm.memory` 和 `yier.bubu.jvm.gc`。
- OOM 实验必须是手动入口，不能被普通单元测试自动触发。
- 默认运行命令使用较小内存参数，避免长时间消耗本机资源。
- Direct Memory 实验需要可配置分配总量、块大小和是否写入内存。
- Metaspace 实验优先使用仓库已有 `MinimalClassFile` 思路，避免为了单个实验引入复杂字节码库。
- GC pressure 实验应提供可调参数，例如对象大小、保留比例、运行秒数。

## 5. 文档设计

建议新增：

- `jvm/docs/labs/jl-02-memory-gc-lab.md`
- `jvm/docs/runbooks/jl-02-heap-oom-runbook.md`
- `jvm/docs/runbooks/jl-02-direct-memory-oom-runbook.md`
- `jvm/docs/runbooks/jl-02-metaspace-oom-runbook.md`
- `jvm/docs/reports/jl-02-heap-oom-report-template.md`

Heap OOM 复盘至少包含：

```text
实验名称：Heap OOM 排查
1. 现象
2. JVM 参数
3. 排查工具
4. 关键证据
5. 根因
6. 解决方案
7. 总结
```

## 6. 验收标准

- `mvn -pl jvm -am test` 通过。
- `mvn -pl jvm -am -DskipTests package` 后，每个实验都有完整 `java -cp ...` 运行命令。
- Heap OOM 能生成 heap dump，文档说明 heap dump 文件不提交。
- Direct Memory OOM 文档明确说明 heap dump 不一定直接展示堆外占用。
- Metaspace OOM 文档说明类卸载依赖 ClassLoader 可达性。
- GC 日志实验同时提供 Java 8 和 JDK 9+ 的参数写法，避免版本混淆。

## 7. 非目标

- 不要求仓库内集成 MAT 或 VisualVM。
- 不提交大型 `.hprof`、`.jfr`、`.log` 运行产物。
- 不把 OOM 实验接入自动测试。
