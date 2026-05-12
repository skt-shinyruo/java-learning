# Java 内存：规范视角 vs HotSpot 进程视角

本模块用两套口径讲清楚“Java 内存”：

1. **Java 虚拟机规范层面的运行时数据区**：偏概念与抽象，回答“JVM 逻辑上有哪些区域”。
2. **HotSpot/OpenJDK 实现层面的进程内存组成**：偏排查与落地，回答“为什么 `RSS` 很大 / 容器为什么 OOM / 堆没满为什么也会炸”。

文档里配了可运行的 demo（见本文“学习辅助代码”一节），便于你把概念和真实指标对应起来。

---

## 1) JVM 规范：运行时数据区（概念模块）

规范层只描述逻辑区域，不规定具体 JVM 必须怎么实现。

<svg viewBox="0 0 1040 430" role="img" aria-label="JVM 规范层运行时数据区" xmlns="http://www.w3.org/2000/svg">
  <style>
    .title { font: 700 26px sans-serif; fill: #172033; }
    .group-title { font: 700 18px sans-serif; fill: #172033; }
    .box-title { font: 700 16px sans-serif; fill: #172033; }
    .body { font: 14px sans-serif; fill: #334155; }
    .panel { fill: #f8fafc; stroke: #cbd5e1; stroke-width: 1.4; }
    .shared { fill: #eef7ff; stroke: #93c5fd; }
    .private { fill: #f5f3ff; stroke: #c4b5fd; }
    .item { fill: #ffffff; stroke: #cbd5e1; }
  </style>
  <rect x="20" y="20" width="1000" height="390" rx="14" class="panel"/>
  <text x="520" y="58" text-anchor="middle" class="title">JVM 运行时数据区（规范层）</text>

  <rect x="55" y="90" width="450" height="285" rx="12" class="shared"/>
  <text x="280" y="122" text-anchor="middle" class="group-title">线程共享</text>
  <rect x="85" y="145" width="390" height="82" rx="10" class="item"/>
  <text x="105" y="176" class="box-title">Java 堆 Heap</text>
  <text x="105" y="202" class="body">对象实例 / 数组；通常由 GC 管理</text>
  <rect x="85" y="250" width="390" height="92" rx="10" class="item"/>
  <text x="105" y="281" class="box-title">方法区 Method Area</text>
  <text x="105" y="307" class="body">类元数据 / 运行时常量池</text>
  <text x="105" y="330" class="body">不同实现可对应 PermGen / Metaspace</text>

  <rect x="535" y="90" width="450" height="285" rx="12" class="private"/>
  <text x="760" y="122" text-anchor="middle" class="group-title">线程私有</text>
  <rect x="565" y="145" width="390" height="58" rx="10" class="item"/>
  <text x="585" y="176" class="box-title">程序计数器 PC Register</text>
  <text x="585" y="195" class="body">当前线程执行字节码的位置指示</text>
  <rect x="565" y="220" width="390" height="82" rx="10" class="item"/>
  <text x="585" y="251" class="box-title">Java 虚拟机栈 JVM Stack</text>
  <text x="585" y="277" class="body">栈帧：局部变量表 / 操作数栈</text>
  <text x="585" y="294" class="body">动态链接 / 方法返回信息</text>
  <rect x="565" y="320" width="390" height="50" rx="10" class="item"/>
  <text x="585" y="350" class="box-title">本地方法栈 Native Method Stack</text>
  <text x="585" y="368" class="body">JNI / native 方法调用相关的栈</text>
</svg>

---

## 2) HotSpot/OpenJDK：进程内存（OS/RSS/容器视角）

从操作系统/容器视角看，一个 JVM 进程的内存通常由多块组成（其中部分可通过 JVM 参数设置上限，但并不存在一个“统一的本地内存默认上限”来覆盖所有 native 分配）。

<svg viewBox="0 0 1280 880" role="img" aria-label="HotSpot JDK 8+ JVM 进程内存" xmlns="http://www.w3.org/2000/svg">
  <style>
    .title { font: 700 28px sans-serif; fill: #172033; }
    .group-title { font: 700 17px sans-serif; fill: #172033; }
    .box-title { font: 700 14px sans-serif; fill: #172033; }
    .body { font: 12.5px sans-serif; fill: #334155; }
    .outer { fill: #f8fafc; stroke: #cbd5e1; stroke-width: 1.4; }
    .heap-panel { fill: #ecfdf5; stroke: #86efac; }
    .thread-panel { fill: #eff6ff; stroke: #93c5fd; }
    .native-panel { fill: #fff7ed; stroke: #fdba74; }
    .os-panel { fill: #f5f3ff; stroke: #c4b5fd; }
    .box { fill: #ffffff; stroke: #d1d5db; }
  </style>
  <rect x="20" y="20" width="1240" height="835" rx="16" class="outer"/>
  <text x="640" y="58" text-anchor="middle" class="title">HotSpot JDK 8+ JVM 进程内存</text>

  <rect x="45" y="88" width="260" height="730" rx="12" class="heap-panel"/>
  <text x="175" y="120" text-anchor="middle" class="group-title">Java 堆 Heap</text>
  <rect x="65" y="145" width="220" height="48" rx="9" class="box"/>
  <text x="82" y="174" class="box-title">参数：-Xms / -Xmx</text>
  <rect x="65" y="208" width="220" height="68" rx="9" class="box"/>
  <text x="82" y="235" class="box-title">对象数据</text>
  <text x="82" y="258" class="body">对象实例 / 数组对象 / String 对象</text>
  <rect x="65" y="292" width="220" height="88" rx="9" class="box"/>
  <text x="82" y="320" class="box-title">Class 对象与 static</text>
  <text x="82" y="343" class="body">java.lang.Class 对象在堆中</text>
  <text x="82" y="364" class="body">static 字段值通常关联在 Class 对象上</text>
  <rect x="65" y="396" width="220" height="122" rx="9" class="box"/>
  <text x="82" y="424" class="box-title">GC 分代 / 分区结构</text>
  <text x="82" y="447" class="body">Young / Eden / Survivor</text>
  <text x="82" y="468" class="body">Old</text>
  <text x="82" y="489" class="body">Region / Humongous Region</text>
  <rect x="65" y="535" width="220" height="78" rx="9" class="box"/>
  <text x="82" y="562" class="box-title">实现差异</text>
  <text x="82" y="585" class="body">传统分代 GC 常见 Young + Old</text>
  <text x="82" y="606" class="body">G1 / ZGC 等更偏向分区模型</text>

  <rect x="325" y="88" width="240" height="730" rx="12" class="thread-panel"/>
  <text x="445" y="120" text-anchor="middle" class="group-title">每个 Java 线程私有区域</text>
  <rect x="345" y="145" width="200" height="58" rx="9" class="box"/>
  <text x="362" y="174" class="box-title">程序计数器 PC Register</text>
  <rect x="345" y="220" width="200" height="122" rx="9" class="box"/>
  <text x="362" y="248" class="box-title">Java 虚拟机栈</text>
  <text x="362" y="271" class="body">栈帧 Stack Frame</text>
  <text x="362" y="292" class="body">局部变量表 / 操作数栈</text>
  <text x="362" y="313" class="body">动态链接 / 方法返回信息</text>
  <rect x="345" y="360" width="200" height="58" rx="9" class="box"/>
  <text x="362" y="389" class="box-title">本地方法栈</text>
  <text x="362" y="410" class="body">Native Method Stack</text>
  <rect x="345" y="435" width="200" height="78" rx="9" class="box"/>
  <text x="362" y="464" class="box-title">实际内存来源</text>
  <text x="362" y="487" class="body">线程栈实际占用 native memory</text>
  <text x="362" y="508" class="body">受 -Xss 影响</text>

  <rect x="585" y="88" width="430" height="730" rx="12" class="native-panel"/>
  <text x="800" y="120" text-anchor="middle" class="group-title">本地内存 Native Memory</text>
  <rect x="605" y="145" width="190" height="134" rx="9" class="box"/>
  <text x="622" y="172" class="box-title">Metaspace（Java 8+）</text>
  <text x="622" y="194" class="body">MetaspaceSize / MaxMetaspaceSize</text>
  <text x="622" y="215" class="body">类元信息 / 字段 / 方法</text>
  <text x="622" y="236" class="body">字节码相关结构 / 常量池元数据</text>
  <text x="622" y="257" class="body">注解 / 方法表 / 接口表 / 类加载器</text>
  <rect x="810" y="145" width="185" height="58" rx="9" class="box"/>
  <text x="827" y="172" class="box-title">PermGen 永久代</text>
  <text x="827" y="194" class="body">Java 7- / PermSize / MaxPermSize</text>
  <rect x="810" y="220" width="185" height="58" rx="9" class="box"/>
  <text x="827" y="247" class="box-title">Compressed Class Space</text>
  <text x="827" y="269" class="body">压缩类指针相关类元数据</text>
  <rect x="605" y="296" width="190" height="78" rx="9" class="box"/>
  <text x="622" y="323" class="box-title">Code Cache</text>
  <text x="622" y="345" class="body">ReservedCodeCacheSize</text>
  <text x="622" y="366" class="body">JIT 机器码 / JVM stub / 入口跳转</text>
  <rect x="810" y="296" width="185" height="78" rx="9" class="box"/>
  <text x="827" y="323" class="box-title">Direct Memory</text>
  <text x="827" y="345" class="body">MaxDirectMemorySize</text>
  <text x="827" y="366" class="body">DirectByteBuffer 背后堆外内存</text>
  <rect x="605" y="392" width="190" height="98" rx="9" class="box"/>
  <text x="622" y="419" class="box-title">GC 内部数据结构</text>
  <text x="622" y="441" class="body">Card Table / Remembered Set</text>
  <text x="622" y="462" class="body">Mark Bitmap / GC worker 数据</text>
  <text x="622" y="483" class="body">各类 GC 辅助结构</text>
  <rect x="810" y="392" width="185" height="98" rx="9" class="box"/>
  <text x="827" y="419" class="box-title">JVM 内部 C/C++ 结构</text>
  <text x="827" y="441" class="body">Symbol / String Table</text>
  <text x="827" y="462" class="body">System Dictionary</text>
  <text x="827" y="483" class="body">ClassLoaderData / 管理对象</text>
  <rect x="605" y="508" width="190" height="78" rx="9" class="box"/>
  <text x="622" y="535" class="box-title">JNI / native 库</text>
  <text x="622" y="557" class="body">第三方 native 库 malloc</text>
  <text x="622" y="578" class="body">通常不受 DirectMemory 限制</text>
  <rect x="810" y="508" width="185" height="78" rx="9" class="box"/>
  <text x="827" y="535" class="box-title">NIO / mmap</text>
  <text x="827" y="557" class="body">映射文件 / 共享库</text>
  <text x="827" y="578" class="body">RSS 取决于访问热度</text>
  <rect x="605" y="604" width="190" height="58" rx="9" class="box"/>
  <text x="622" y="633" class="box-title">JIT 编译器工作内存</text>
  <rect x="810" y="604" width="185" height="58" rx="9" class="box"/>
  <text x="827" y="633" class="box-title">JVM 进程 / 动态库 / 运行时</text>

  <rect x="1035" y="88" width="200" height="730" rx="12" class="os-panel"/>
  <text x="1135" y="120" text-anchor="middle" class="group-title">OS / 容器侧相关</text>
  <rect x="1055" y="145" width="160" height="92" rx="9" class="box"/>
  <text x="1072" y="172" class="box-title">进程可用内存上限</text>
  <text x="1072" y="194" class="body">物理机内存</text>
  <text x="1072" y="215" class="body">ulimit / cgroup limit</text>
  <rect x="1055" y="255" width="160" height="112" rx="9" class="box"/>
  <text x="1072" y="282" class="box-title">OS Page Cache</text>
  <text x="1072" y="304" class="body">不等同于 JVM 自己内存</text>
  <text x="1072" y="325" class="body">但会影响机器 / 容器</text>
  <text x="1072" y="346" class="body">整体内存压力</text>
</svg>

方法区在 HotSpot JDK 8+ 中的落地关系：

<svg viewBox="0 0 980 210" role="img" aria-label="方法区在 HotSpot JDK 8+ 中的落地关系" xmlns="http://www.w3.org/2000/svg">
  <style>
    .box { fill: #ffffff; stroke: #cbd5e1; stroke-width: 1.4; }
    .method { fill: #fefce8; stroke: #fde047; }
    .native { fill: #fff7ed; stroke: #fdba74; }
    .heap { fill: #ecfdf5; stroke: #86efac; }
    .text { font: 700 15px sans-serif; fill: #172033; }
    .small { font: 13px sans-serif; fill: #334155; }
    .arrow { stroke: #64748b; stroke-width: 2; fill: none; marker-end: url(#arrow); }
    .dash { stroke-dasharray: 6 5; }
  </style>
  <defs>
    <marker id="arrow" markerWidth="10" markerHeight="10" refX="7" refY="3" orient="auto">
      <path d="M0,0 L0,6 L8,3 z" fill="#64748b"/>
    </marker>
  </defs>
  <rect x="30" y="62" width="210" height="82" rx="10" class="method"/>
  <text x="135" y="95" text-anchor="middle" class="text">规范：方法区</text>
  <text x="135" y="120" text-anchor="middle" class="small">Method Area</text>

  <rect x="340" y="35" width="190" height="64" rx="10" class="native"/>
  <text x="435" y="72" text-anchor="middle" class="text">Metaspace</text>
  <rect x="340" y="122" width="190" height="64" rx="10" class="native"/>
  <text x="435" y="159" text-anchor="middle" class="text">Compressed Class Space</text>
  <rect x="610" y="62" width="180" height="82" rx="10" class="heap"/>
  <text x="700" y="95" text-anchor="middle" class="text">java.lang.Class</text>
  <text x="700" y="120" text-anchor="middle" class="small">对象在 Java 堆中</text>
  <rect x="830" y="62" width="120" height="82" rx="10" class="box"/>
  <text x="890" y="95" text-anchor="middle" class="text">Java Heap</text>
  <text x="890" y="120" text-anchor="middle" class="small">对象区域</text>

  <path d="M240 92 C280 78, 300 68, 340 67" class="arrow"/>
  <path d="M240 118 C280 135, 300 153, 340 154" class="arrow"/>
  <path d="M530 67 C575 70, 585 84, 610 94" class="arrow dash"/>
  <path d="M530 154 C575 150, 585 128, 610 116" class="arrow dash"/>
  <path d="M790 103 L830 103" class="arrow"/>
</svg>

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
