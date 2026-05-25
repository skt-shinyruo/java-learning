# JL-02-LAB-01 Heap OOM 排查 Runbook

## 1. 启动实验

```bash
mvn -pl jvm -am -DskipTests package

java -Xms64m -Xmx64m \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=target/jvm-lab-heap.hprof \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp heap-oom --mb 128 --chunkMb 1 --reportEvery 8
```

## 2. 预期现象

程序持续把 `byte[]` 放入 `ArrayList`，最终抛出 `java.lang.OutOfMemoryError: Java heap space`，并在 `target/jvm-lab-heap.hprof` 生成 heap dump。

## 3. 观察命令

```bash
jcmd <pid> GC.heap_info
jcmd <pid> GC.class_histogram
jmap -dump:live,format=b,file=target/jvm-lab-live.hprof <pid>
```

## 4. MAT / VisualVM 观察点

- Dominator Tree 中 `byte[]` 占用最大。
- `ArrayList.elementData` 持有大量 `byte[]`。
- `ArrayList` 由 `HeapOomDemo.run` 的局部变量链路保持可达。

## 5. 根因判断

这不是 GC 不工作，而是对象仍然从 GC Roots 可达。集合持续持有引用，导致 `byte[]` 无法回收。

## 6. 修复方向

- 给集合或缓存设置容量上限。
- 使用淘汰策略。
- 分批处理并释放引用。
- 避免把大对象挂到长生命周期静态字段或线程局部变量上。
