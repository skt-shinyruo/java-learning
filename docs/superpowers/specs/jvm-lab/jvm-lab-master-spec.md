# JVM 实战靶场总 Spec

## 1. 目标

在当前 `java-learning` 仓库的 `jvm` 模块内建设一个可持续扩展的 JVM 实战靶场。靶场不以“堆砌 Demo”为目标，而是围绕真实生产问题组织实验：制造问题、运行程序、使用 JVM 工具观察、分析机制、修改代码或参数、验证效果，并形成复盘文档。

完成后，仓库应同时具备三类资产：

- 可运行的最小实验程序，覆盖 JVM 核心机制和常见故障。
- 可复现的排查步骤，使用 `javap`、`jcmd`、`jstack`、`jmap`、`jstat`、JFR、MAT、Arthas 等工具。
- 中文复盘文档，把现象、证据、根因和优化方案沉淀下来。

## 2. 父子 Spec 关系

本总 spec 是后续所有 JVM 靶场工作的父级规格。每个类别拥有独立子 spec，后续实现计划和提交应引用对应编号。

| 编号 | 子 Spec | 能力方向 | 建议批次 |
| --- | --- | --- | --- |
| JL-01 | [字节码与类加载](./jl-01-bytecode-classloading-spec.md) | `javap`、Class 文件解析、自定义 ClassLoader、类型身份 | 第 2 批 |
| JL-02 | [内存模型与 GC](./jl-02-memory-gc-spec.md) | Heap OOM、Direct Memory OOM、Metaspace OOM、GC 日志 | 第 1 批 |
| JL-03 | [线程、锁与 JMM](./jl-03-thread-lock-jmm-spec.md) | volatile、死锁、线程栈、锁与计数器性能 | 第 2 批 |
| JL-04 | [JIT 与性能优化](./jl-04-jit-performance-spec.md) | JMH、预热、死代码消除、逃逸分析、内联 | 第 2 批 |
| JL-05 | [线上故障排查](./jl-05-troubleshooting-spec.md) | 高 CPU、内存泄漏、线程阻塞、频繁 GC、排查 Runbook | 第 1 批 |
| JL-06 | [Java Agent 与字节码增强](./jl-06-java-agent-spec.md) | Instrumentation、ByteBuddy、premain、agentmain、方法耗时 | 第 3 批 |
| JL-07 | [Mini JVM Doctor](./jl-07-mini-jvm-doctor-spec.md) | JVM 诊断小工具、报告生成、JFR、heap dump | 第 3 批 |

## 3. 当前仓库约束

- 仓库是 Java 8 多模块 Maven 项目，`jvm` 模块已有基础 JVM 示例。
- Java 代码应优先保持 Java 8 兼容，JFR、JDK 17/21 或新 GC 特性作为扩展实验明确标注版本要求。
- 文档源文件放在 `jvm/docs/` 或规划文档目录，不能修改 `mkdocs/site/` 生成内容。
- 不修改 `references/`，除非用户后续明确要求。
- 示例应保持小而清晰，不引入重型框架作为默认依赖。JMH、ByteBuddy、ASM 等只在对应实验需要时引入。

## 4. 总体架构

靶场沿用现有 `jvm` Maven 模块，避免新建独立仓库。后续实现时建议逐步整理为：

```text
jvm/
├── src/main/java/yier/bubu/jvm/
│   ├── bytecode/
│   ├── classloading/
│   ├── memory/
│   ├── gc/
│   ├── jmm/
│   ├── jit/
│   ├── troubleshooting/
│   ├── agent/
│   └── doctor/
├── src/test/java/yier/bubu/jvm/
└── docs/
    ├── labs/
    ├── reports/
    └── runbooks/
```

已有 `JvmMemoryApp`、`DirectMemoryDemo`、`MetaspaceDemo`、`StackOverflowDemo` 等代码可作为迁移或扩展起点，不应盲目重写。

## 5. 实验闭环

每个实验必须符合统一闭环：

```text
构造问题 -> 运行程序 -> 使用工具观察 -> 分析机制 -> 修改代码或参数 -> 验证效果 -> 写复盘
```

每个实验文档至少包含：

- 实验编号和名称。
- 目标和适用 JDK 版本。
- 编译命令和运行命令。
- 预期现象和异常信息。
- 观察工具和关键命令。
- 关键证据，例如线程栈、GC 日志片段、class histogram、heap dump 结论。
- 根因解释。
- 修复或优化方案。
- 验证方式。
- 复盘问题。

## 6. 批次规划

### 6.1 第 1 批：最小可用故障靶场

优先完成最能提升排查能力的实验：

- Heap OOM 与 heap dump 分析。
- Direct Memory OOM。
- Metaspace OOM。
- 死锁与 `jstack` 定位。
- 高 CPU 与 `top -H`、`jstack` 定位。
- GC pressure 与 GC 日志分析。

对应子 spec：JL-02、JL-05。

### 6.2 第 2 批：机制理解与性能实验

在第 1 批已有故障样本基础上补齐机制实验：

- `javap` 分析常见 Java 语法。
- 简化 Class 文件解析器。
- 自定义 ClassLoader 与类型身份实验。
- volatile 可见性和复合操作非原子性。
- JMH 对比 `synchronized`、`ReentrantLock`、`AtomicInteger`、`LongAdder`。
- JMH 入门、逃逸分析和 JIT 行为观察。

对应子 spec：JL-01、JL-03、JL-04。

### 6.3 第 3 批：增强与诊断工具

最后再做工具化能力，避免在没有真实故障样本前过早抽象：

- Java Agent 方法耗时统计。
- 指定包名过滤和异常记录。
- Mini JVM Doctor 查看 JVM 参数、堆、线程、类加载、死锁、heap dump、JFR 和诊断报告。

对应子 spec：JL-06、JL-07。

## 7. 交付物

每一批完成时至少交付：

- `jvm/src/main/java` 下的实验程序或工具代码。
- 必要的 `jvm/src/test/java` 单元测试或行为测试。
- `jvm/docs/labs/` 下的实验说明。
- `jvm/docs/runbooks/` 下的排查手册，适用于故障类实验。
- `jvm/docs/reports/` 下的复盘模板或样例复盘。
- MkDocs 导航或 JVM index 更新，使文档可发现。

## 8. 验收标准

总体验收标准：

- `mvn -pl jvm -am test` 通过，除非某些故障实验明确设计为手动运行且不进入自动测试。
- 每个实验都有可复制运行命令，命令从仓库根目录执行。
- 故障实验不会在普通 `mvn test` 中触发 OOM、死锁或长时间 CPU 占用。
- 文档中的危险命令有清晰提示，并使用较小内存参数作为默认示例。
- MkDocs 能构建通过：`mkdocs build -f mkdocs/mkdocs.yml`。
- 父子 spec 链接完整，后续计划能按 `JL-xx` 追踪。

## 9. 非目标

- 不把当前仓库升级为只支持 JDK 17 或 JDK 21 的项目。
- 不把所有实验合并进一个巨大入口类。
- 不把 MAT、JMC、Arthas 这类外部工具打包进仓库。
- 不把生成的 heap dump、JFR 文件、GC 日志作为长期版本化资产，除非后续明确需要小型样例。
- 不编辑 `references/` 和 `mkdocs/site/`。

## 10. 风险与处理

- OOM 和死锁实验可能影响本机环境：默认命令必须使用小内存和明确入口，普通测试不运行危险逻辑。
- JDK 版本差异会影响 GC 日志、JFR 和 `javap` 输出：文档必须标注 Java 8、JDK 11+、JDK 17/21 的差异。
- 微基准测试容易误导：JIT 相关实验必须使用 JMH，不用手写 `System.nanoTime()` 作为结论依据。
- Agent 和诊断工具容易过度设计：先满足本仓库实验场景，再考虑扩展接口。

## 11. 后续跟踪方式

- 每个实现计划标题应包含对应编号，例如 `JL-02 内存与 GC 第 1 批实现计划`。
- 每个实验文档使用 `JL-xx-LAB-yy` 编号。
- 每个复盘报告引用实验编号、运行命令、JDK 版本和关键证据。
- 每批结束后更新本总 spec 的完成状态或新增一个批次总结文档。
