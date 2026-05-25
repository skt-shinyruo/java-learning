# JL-06 Java Agent 与字节码增强 Spec

父级规格：[JVM 实战靶场总 Spec](./jvm-lab-master-spec.md)

## 1. 目标

建设 Java Agent 实验，理解 Arthas、APM 和运行时字节码增强的基础机制。最终实现一个简单 Agent：对指定包名下的 public 方法打印执行耗时，并能记录异常。

## 2. 范围

本 spec 覆盖：

- Java Instrumentation API。
- `premain` 静态加载。
- `agentmain` 动态 attach 作为扩展。
- ByteBuddy 或 ASM 增强。
- 包名过滤。
- 方法耗时统计。
- 异常记录。

## 3. 实验清单

| 实验编号 | 名称 | 交付重点 |
| --- | --- | --- |
| JL-06-LAB-01 | 最小 premain Agent | `-javaagent` 启动、打印加载信息 |
| JL-06-LAB-02 | 方法耗时增强 | 指定包名 public 方法耗时 |
| JL-06-LAB-03 | 异常记录 | 方法抛异常时记录类型和耗时 |
| JL-06-LAB-04 | 过滤规则 | include package、exclude class、开关参数 |
| JL-06-LAB-05 | 动态 attach 扩展 | `agentmain` 与 Attach API，作为可选实验 |

## 4. 代码设计约束

- Agent 可作为 `jvm` 模块内 profile 构建，也可在后续计划中拆成 `jvm-agent` 子模块；具体取决于 Maven 配置复杂度。
- 默认优先 ByteBuddy，除非后续明确需要 ASM 级别教学。
- Agent 参数必须简单，例如：

```text
-javaagent:target/jvm-agent.jar=include=yier.bubu.jvm
```

- 不增强 JDK 核心类。
- 不在普通测试中默认启用 Agent。

## 5. 文档设计

建议新增：

- `jvm/docs/labs/jl-06-java-agent-lab.md`
- `jvm/docs/runbooks/jl-06-javaagent-runbook.md`
- `jvm/docs/reports/jl-06-agent-enhancement-report.md`

文档必须解释：

- `premain` 和 `agentmain` 的区别。
- 类加载前增强和已加载类 retransform 的区别。
- 为什么包名过滤是必要的。
- Agent 可能带来的性能和稳定性风险。

## 6. 验收标准

- 能构建出可被 `-javaagent` 使用的 jar。
- 对指定包名方法输出方法名、耗时和异常信息。
- 不增强仓库无关类或 JDK 核心类。
- 文档给出完整启动命令和样例输出。
- 如果使用 ByteBuddy，Maven 依赖范围和打包方式清晰，不污染普通实验运行。

## 7. 非目标

- 不实现生产级 APM。
- 不实现分布式链路追踪后端。
- 不支持复杂表达式匹配语言。
- 不保证对所有第三方框架无侵入增强。
