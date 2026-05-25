# JVM 实战靶场 Spec 索引

本目录保存 JVM 实战靶场的父子规格文档。总 spec 定义目标、边界、批次和跟踪规则；子 spec 定义每个能力类别的实验范围、交付物和验收标准。

## Spec 关系

- [JVM 实战靶场总 Spec](./jvm-lab-master-spec.md)
- [JL-01 字节码与类加载 Spec](./jl-01-bytecode-classloading-spec.md)
- [JL-02 内存模型与 GC Spec](./jl-02-memory-gc-spec.md)
- [JL-03 线程、锁与 JMM Spec](./jl-03-thread-lock-jmm-spec.md)
- [JL-04 JIT 与性能优化 Spec](./jl-04-jit-performance-spec.md)
- [JL-05 线上故障排查 Spec](./jl-05-troubleshooting-spec.md)
- [JL-06 Java Agent 与字节码增强 Spec](./jl-06-java-agent-spec.md)
- [JL-07 Mini JVM Doctor Spec](./jl-07-mini-jvm-doctor-spec.md)

## 跟踪规则

- 总 spec 中的 `JL-xx` 编号必须和子 spec 文件名、标题保持一致。
- 后续每一批实现计划应引用对应子 spec，例如 `来源 Spec：JL-02 内存模型与 GC`。
- 子 spec 的实验编号采用 `JL-xx-LAB-yy`，复盘文档、运行脚本、测试用例和提交信息优先沿用该编号。
