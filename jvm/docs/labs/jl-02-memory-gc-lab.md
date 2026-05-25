# JL-02 内存模型与 GC 实验

父级规格：`docs/superpowers/specs/jvm-lab/jl-02-memory-gc-spec.md`

## 1. 实验目标

本实验组用于制造和观察 JVM 内存问题：Heap OOM、Direct Memory OOM、Metaspace OOM、StackOverflowError 和 GC pressure。每个实验都按“构造问题 -> 运行程序 -> 使用工具观察 -> 分析机制 -> 修改参数或代码 -> 验证效果 -> 写复盘”的闭环执行。

## 2. 编译

```bash
mvn -pl jvm -am -DskipTests package
```

## 3. 实验入口

```bash
java -cp jvm/target/classes yier.bubu.jvm.JvmLabApp help
```

## 4. 实验清单

| 编号 | 场景 | 入口 | 重点证据 |
| --- | --- | --- | --- |
| JL-02-LAB-01 | Heap OOM | `heap-oom` | heap dump、GC Roots、`byte[]` retained heap |
| JL-02-LAB-02 | Direct Memory OOM | `direct-oom` | BufferPoolMXBean、NMT、直接内存上限 |
| JL-02-LAB-03 | Metaspace OOM | `metaspace-oom` | Metaspace pool、ClassLoader 可达性 |
| JL-02-LAB-04 | StackOverflowError | `stack-overflow` | 递归深度、`-Xss` 差异 |
| JL-02-LAB-05 | GC pressure | `gc-pressure` | GC 日志、`jstat -gcutil`、晋升和分配速率 |

## 5. 运行命令

### 5.1 Heap OOM

```bash
java -Xms64m -Xmx64m \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=target/jvm-lab-heap.hprof \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp heap-oom --mb 128 --chunkMb 1 --reportEvery 8
```

### 5.2 Direct Memory OOM

```bash
java -XX:MaxDirectMemorySize=64m \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp direct-oom --mb 96 --chunkMb 4 --touch true --reportEvery 4
```

### 5.3 Metaspace OOM

```bash
java -XX:MaxMetaspaceSize=64m \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp metaspace-oom --count 20000 --reportEvery 1000
```

### 5.4 StackOverflowError

```bash
java -Xss256k \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp stack-overflow
```

### 5.5 GC pressure

JDK 8：

```bash
java -Xms128m -Xmx128m \
  -XX:+UseG1GC \
  -XX:+PrintGCDetails \
  -XX:+PrintGCDateStamps \
  -Xloggc:target/jvm-lab-gc.log \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp gc-pressure --seconds 60 --chunkKb 256 --retainEvery 8 --maxRetained 256
```

JDK 9+：

```bash
java -Xms128m -Xmx128m \
  -XX:+UseG1GC \
  -Xlog:gc*:file=target/jvm-lab-gc.log:time,uptime,level,tags \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp gc-pressure --seconds 60 --chunkKb 256 --retainEvery 8 --maxRetained 256
```

## 6. 产物处理

`target/*.hprof`、`target/*.log` 和本地工具导出的分析文件不提交到仓库。复盘只提交必要的结论、关键小片段和截图说明。
