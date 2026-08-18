https://rocketmq.apache.org/zh/docs/bestPractice/07JVMOS

JVM/OS配置
本小节主要介绍系统（JVM/OS）相关的配置。

1 JVM选项
推荐使用最新发布的 JDK 版本。通过设置相同的 Xms 和 Xmx 值来防止 JVM 调整堆大小以获得更好的性能。生产环境 JVM 配置如下所示：

-server -Xms8g -Xmx8g -Xmn4g 

当 JVM 是默认 8 字节对齐，建议配置最大堆内存不要超过 32 G，否则会影响 JVM 的指针压缩技术，浪费内存。

### 为什么 8 字节对齐通常对应约 32 GiB 堆

这里的“8 字节对齐”不是说指针占 8 字节，而是说 Java 对象的起始地址是 8 的倍数。

在 64 位 HotSpot JVM 中，未压缩的对象引用通常需要 8 字节。开启压缩对象指针（Compressed Oops）后，JVM 可以只保存一个 32 位，即 4 字节的偏移量。解码时再将该偏移量乘以 8：

```text
真实地址 = 堆基地址 + (压缩后的 32 位数值 << 3)
                                              ^
                                            乘以 8
```

因为对象按 8 字节对齐，地址的最低 3 位始终为 `000`，因此不需要存储这 3 位。32 位偏移量理论上可以覆盖的范围为：

```text
2^32 * 8 字节 = 2^35 字节 = 32 GiB
```

当堆超过该范围时，HotSpot 可能关闭压缩对象指针，使对象引用从 4 字节恢复为 8 字节。这会导致：

- 对象中的引用字段变大。
- 引用数组的元素占用空间接近翻倍。
- CPU 缓存中能容纳的对象和引用变少。
- GC 需要扫描和搬运更多数据。

例如，一个包含 4 个对象引用字段的对象，仅这些字段的空间就可能从：

```text
4 * 4 = 16 字节
```

变为：

```text
4 * 8 = 32 字节
```

因此可能出现一个反直觉现象：一个刚超过压缩指针范围的堆，实际能容纳的对象数量未必比稍小但仍开启压缩指针的堆更多。

32 GiB 是默认 8 字节对齐下的理论范围，不是所有 JDK 和平台上都完全相同的硬边界。可以用下列命令验证当前 JVM 在不同堆大小下是否开启压缩指针：

```bash
java -Xmx31g -XX:+PrintFlagsFinal -version 2>&1 | grep UseCompressedOops
java -Xmx32g -XX:+PrintFlagsFinal -version 2>&1 | grep UseCompressedOops
```

输出中 `UseCompressedOops = true` 表示压缩对象指针仍然开启。生产环境通常会在实际切换点以下留出一定余量，而不是将 `-Xmx` 刚好设置在 32 GiB。

如果您不关心 RocketMQ Broker 的启动时间，还有一种更好的选择，就是通过 “预触摸” Java 堆以确保在JVM初始化期间每个页面都将被分配。那些不关心启动时间的人可以启用它：

-XX:+AlwaysPreTouch  

信息
生产环境集群 Broker 一般建议配置足够的内存，避免使用小规格内存机器部署。因为Broker是重度依赖内存PageCache做性能优化的，内存过小可能造成性能不稳定。

禁用偏置锁定可能会减少 JVM 暂停：

-XX:-UseBiasedLocking   

垃圾回收，建议使用 JDK 1.8 自带的 G1 收集器：

-XX:+UseG1GC 
-XX:G1HeapRegionSize=16m   
-XX:G1ReservePercent=25 
-XX:InitiatingHeapOccupancyPercent=30

这些 GC 选项看起来有点激进，但事实证明它在我们的生产环境中具有良好的性能。

另外不要把 -XX:MaxGCPauseMillis 的值设置太小，否则 JVM 将使用一个小的年轻代来实现这个目标，这将导致非常频繁的 minor GC，所以建议使用 rolling GC 日志文件：

-XX:+UseGCLogFileRotation   
-XX:NumberOfGCLogFiles=5 
-XX:GCLogFileSize=30m

如果写入 GC 文件会增加代理的延迟，可以考虑将 GC 日志文件重定向到内存文件系统：

-Xloggc:/dev/shm/mq_gc_%p.log123   

2 Linux内核参数
os.sh 脚本在 bin 文件夹中列出了许多内核参数，可以进行微小的更改然后用于生产用途。下面的参数需要注意，更多细节请参考 /proc/sys/vm/*的 文档

vm.extra_free_kbytes 告诉 VM 在后台回收（kswapd）启动的阈值与直接回收（通过分配进程）的阈值之间保留额外的可用内存。RocketMQ 使用此参数来避免内存分配中的长延迟。（与具体内核版本相关）
vm.min_free_kbytes 如果将其设置为低于 1024 KB，将会巧妙的将系统破坏，并且系统在高负载下容易出现死锁。
vm.max_map_count 限制一个进程可能具有的最大内存映射区域数。RocketMQ 将使用 MMAP 加载 CommitLog 和 ConsumeQueue，因此建议将为此参数设置较大的值。
vm.swappiness 定义内核交换内存页面的积极程度。较高的值会增加攻击性，较低的值会减少交换量。建议将值设置为 10 来避免交换延迟。
File descriptor limits RocketMQ 需要为文件（ CommitLog 和 ConsumeQueue ）和网络连接打开文件描述符。我们建议设置文件描述符的值为 655350。
Disk scheduler RocketMQ建议使用I/O截止时间调度器，它试图为请求提供有保证的延迟。
