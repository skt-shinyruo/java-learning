# JL-02-LAB-02 Direct Memory OOM 排查 Runbook

## 1. 启动实验

```bash
mvn -pl jvm -am -DskipTests package

java -XX:MaxDirectMemorySize=64m \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp direct-oom --mb 96 --chunkMb 4 --touch true --reportEvery 4
```

## 2. 预期现象

程序使用 `ByteBuffer.allocateDirect()` 分配直接内存并持有引用。当直接内存超过 `MaxDirectMemorySize` 后，可能抛出 direct buffer memory 相关 OOM。

## 3. 观察命令

```bash
jcmd <pid> VM.native_memory summary
jcmd <pid> GC.heap_info
jcmd <pid> GC.class_histogram
```

Native Memory Tracking 需要启动时开启：

```bash
-XX:+UnlockDiagnosticVMOptions -XX:NativeMemoryTracking=summary
```

## 4. 关键结论

直接内存属于本地内存，不是 Java heap。Heap dump 能看到 `DirectByteBuffer` 对象和引用链，但不一定直接展示全部堆外内存占用。

## 5. 修复方向

- 控制 direct buffer 池大小。
- 避免无界缓存 direct buffer。
- 检查 Netty 等框架的 direct memory 配置。
- 结合 RSS、NMT 和 BufferPoolMXBean 判断堆外占用。
