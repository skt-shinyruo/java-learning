# Java 内存：规范视角 vs HotSpot 进程视角

本模块用两套口径讲清楚“Java 内存”：

1. **Java 虚拟机规范层面的运行时数据区**：偏概念与抽象，回答“JVM 逻辑上有哪些区域”。
2. **HotSpot/OpenJDK 实现层面的进程内存组成**：偏排查与落地，回答“为什么 `RSS` 很大 / 容器为什么 OOM / 堆没满为什么也会炸”。

文档里配了可运行的 demo（见本文“学习辅助代码”一节），便于你把概念和真实指标对应起来。

---

## 1) JVM 规范：运行时数据区（概念模块）

规范层只描述逻辑区域，不规定具体 JVM 必须怎么实现。

![JVM 规范层运行时数据区](images/jvm-runtime-data-areas.svg)

---

## 2) HotSpot/OpenJDK：进程内存（OS/RSS/容器视角）

从操作系统/容器视角看，一个 JVM 进程的内存通常由多块组成（其中部分可通过 JVM 参数设置上限，但并不存在一个“统一的本地内存默认上限”来覆盖所有 native 分配）。

![HotSpot JDK 8+ JVM 进程内存](images/hotspot-process-memory.svg)

方法区在 HotSpot JDK 8+ 中的落地关系：

![方法区在 HotSpot JDK 8+ 中的落地关系](images/method-area-hotspot.svg)

几个容易混淆的点：

- **方法区是规范概念**，元空间是 HotSpot 对方法区的主要实现。
- **元空间属于本地内存**，不属于 Java 堆。
- **`java.lang.Class` 对象在堆里**，但它指向的类元数据主要在元空间里。
- **Direct Memory 不是方法区**，它也是本地内存的一部分。
- **线程栈在规范里是 JVM 栈/本地方法栈，但实际内存来自 native memory。**
- **Code Cache 存 JIT 后的机器码，不是 Java 堆，也通常不算元空间。**

一句话版：

> Java 堆主要放对象；线程栈放方法调用过程；元空间放类元数据；Code Cache 放 JIT 后的机器码；Direct Memory 放堆外 buffer；这些堆外部分总体都属于 JVM 进程使用的本地内存。

---

## 3) 学习辅助代码（本模块自带 demo）

> 注意：某些 demo 会刻意制造 OOM/异常，建议在可控环境运行，并先从较小参数开始。

### 编译

在项目根目录执行：

```bash
mvn -pl jvm -am -DskipTests package
```

### 运行入口

```bash
java -cp jvm/target/classes yier.bubu.jvm.JvmMemoryApp help
```

### 3.1 inspect：打印 JVM/内存/线程/类加载概要

```bash
java -cp jvm/target/classes yier.bubu.jvm.JvmMemoryApp inspect
```

### 3.2 direct：分配 DirectByteBuffer，观察 Direct Memory 增长

推荐用一个很小的 direct 上限来快速观察现象：

```bash
java -XX:MaxDirectMemorySize=64m -cp jvm/target/classes yier.bubu.jvm.JvmMemoryApp direct --mb 96 --chunkMb 4 --touch true --reportEvery 4
```

### 3.3 metaspace：加载大量类，观察 Metaspace 增长

```bash
java -XX:MaxMetaspaceSize=64m -cp jvm/target/classes yier.bubu.jvm.JvmMemoryApp metaspace --count 20000 --reportEvery 1000
```

### 3.4 stack：触发 StackOverflowError，观察 -Xss 的影响

```bash
java -Xss256k -cp jvm/target/classes yier.bubu.jvm.JvmMemoryApp stack
```

---

## 4)（可选）观察/排查的常用命令

- 打印 JVM 最终采用的 flags（含人体工学结果）：`java -XX:+PrintFlagsFinal -version`
- Native Memory Tracking（NMT，需启动时开启，注意开销）：
  - 启动参数：`-XX:+UnlockDiagnosticVMOptions -XX:NativeMemoryTracking=summary`
  - 查看：`jcmd <pid> VM.native_memory summary`
