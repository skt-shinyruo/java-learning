# JL-05-LAB-02 静态集合内存泄漏 Runbook

## 1. 启动实验

```bash
mvn -pl jvm -am -DskipTests package

java -Xms128m -Xmx128m \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp static-leak --mb 96 --chunkMb 1 --reportEvery 8 --sleepSeconds 300
```

## 2. 排查命令

```bash
jcmd <pid> GC.heap_info
jcmd <pid> GC.class_histogram
jmap -dump:live,format=b,file=target/jvm-lab-static-leak.hprof <pid>
```

## 3. 观察点

- `byte[]` 数量和占用靠前。
- heap dump 中 `StaticMemoryLeakDemo.RETAINED` 持有对象。
- GC 后对象仍然可达。

## 4. 根因

静态集合生命周期接近整个 JVM 进程。只要集合不清理，其中对象就不会被 GC 回收。

## 5. 修复方向

- 限制缓存大小。
- 使用过期和淘汰策略。
- 在生命周期结束时清理静态集合。
