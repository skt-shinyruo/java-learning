# JL-05-LAB-06 频繁 GC 排查 Runbook

## 1. 启动实验

JDK 8：

```bash
mvn -pl jvm -am -DskipTests package

java -Xms128m -Xmx128m \
  -XX:+UseG1GC \
  -XX:+PrintGCDetails \
  -XX:+PrintGCDateStamps \
  -Xloggc:target/jvm-lab-gc.log \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp gc-pressure --seconds 120 --chunkKb 256 --retainEvery 4 --maxRetained 512
```

JDK 9+：

```bash
java -Xms128m -Xmx128m \
  -XX:+UseG1GC \
  -Xlog:gc*:file=target/jvm-lab-gc.log:time,uptime,level,tags \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp gc-pressure --seconds 120 --chunkKb 256 --retainEvery 4 --maxRetained 512
```

## 2. 排查命令

```bash
jstat -gcutil <pid> 1000
jcmd <pid> GC.heap_info
jcmd <pid> VM.flags
```

## 3. 判断问题

- Young GC 是否过于频繁。
- Old 区是否持续上涨。
- 是否存在对象大量晋升。
- 是否堆太小。
- 是否有保留对象过多导致回收效果差。

## 4. 修复方向

- 降低分配速率。
- 减少长生命周期对象。
- 调整缓存策略。
- 根据证据调整堆大小和 GC 参数。
