# Kafka `.log`、`.index`、`.timeindex`：`FileChannel`、`mmap` 与 page cache

Kafka 经常被拿来举 `page cache`、`sendfile` 和 `mmap` 的例子，但这几个概念在很多文章里被混在了一起，最常见的混淆有两类：

- `.log` 文件是不是应该主要靠 `mmap` 访问。
- `.index` / `.timeindex` 已经用了 `mmap`，是不是说明 `.log` 也应该跟着一起 `mmap`。

更准确的说法是：

- Kafka `.log` 文件主要通过 `FileChannel` 做顺序追加和顺序发送，底层依赖 OS `page cache`，在合适场景下进一步利用 `sendfile` / `transferTo`。
- Kafka `.index` / `.timeindex` 文件主要通过 `mmap`（`MappedByteBuffer`）做随机定位，底层同样依赖 OS `page cache`。

这里最容易说错的一点是：

```text
不是 `.log` 依赖 page cache，而 `.index` / `.timeindex` 不依赖。
实际上它们都依赖 page cache，只是访问方式不同。
```

## 1. `.log`、`.index`、`.timeindex` 各自做什么

Kafka 的一个 log segment 可以粗略理解成三类文件：

- `.log`：真正存放消息数据。
- `.index`：保存相对 offset 到物理位置的稀疏索引。
- `.timeindex`：保存 timestamp 到 offset/位置的稀疏索引。

如果只看 I/O 模式：

- `.log` 偏向大文件顺序追加、顺序读取、文件到网络发送。
- `.index` / `.timeindex` 偏向小文件随机定位、二分查找、尾部追加。

因此这三类文件虽然围绕同一个 segment 工作，但它们的最优访问路径并不相同。

## 2. 为什么 `.log` 主路径主要用 `FileChannel`

Kafka `.log` 文件的热点，不是“随机改很多小块内存”，而是：

```text
顺序追加消息
顺序追读消息
把文件数据高效发到 socket
```

这正是 `FileChannel + page cache + sendfile` 组合擅长的场景。

可以把简化路径画成：

```text
producer append
    ↓
FileChannel write
    ↓
page cache
    ↓
consumer fetch
    ↓
sendfile / transferTo
    ↓
socket / NIC
```

这种场景下，Kafka 更关心的是：

- 顺序写吞吐是否稳定。
- 消费者读取时能否尽量不经过用户态中转。
- segment 滚动、刷盘、恢复、删除时是否容易控制。

Kafka 4.3 设计文档强调的也是 `page cache + sendfile`：数据先进入 OS `page cache`，随后在消费者追读时尽量直接从 page cache 发到网络，而不是先回到应用层再发出去。

## 3. 为什么 `.index` / `.timeindex` 适合 `mmap`

`.index` / `.timeindex` 更适合 `mmap`，不是因为它们“更高级”，而是因为它们要解决的问题和 `.log` 完全不同。

### 3.1 它们存的是少量元数据，不是消息体

索引文件只存定位信息，不存完整消息体。`OffsetIndex` 的 entry 是固定 `8` 字节，`TimeIndex` 的 entry 是固定 `12` 字节。Broker 查索引时，本质上只是读取很少量的元数据，算出 `.log` 中的大致位置。

### 3.2 它们的访问模式是“小量随机读 + 尾部追加”

索引查找通常是：

- 在文件中间做二分查找。
- 命中后顺着少量 entry 前后扫描。
- 新消息写入时在索引尾部追加少量 entry。

对这种“小而随机”的访问模式，`mmap` 很合适，因为应用可以像读普通内存一样读取少量 entry，而不必每次显式 `read()` 到单独的用户缓冲区。

### 3.3 查索引本来就必须进入用户态做判断

Broker 必须读取索引项，比较 offset 或 timestamp，最后算出 `.log` 中的物理位置。所以这里本来就需要让应用代码“看见”数据。既然用户态本来就要参与，那么 `mmap` 让这一步更自然。

可以把它理解成：

```text
索引文件：用 mmap 做“找地址”
日志文件：用 sendfile 做“搬大货”
```

## 4. 为什么 `.log` 不一定适合改成 `mmap`

很多人会直觉觉得：

```text
mmap 少一次 copy，所以一定更快。
```

这句话对文件访问来说并不普适。对 Kafka `.log` 这种大文件顺序 I/O 主路径，`mmap` 很多情况下反而不如现在这套 `FileChannel + page cache + sendfile` 组合合适，核心原因可以概括成四点。

### 4.1 `.log` 的主访问模式本来就是 `FileChannel` 的强项

`.log` 的关键工作是：

- 顺序追加消息。
- 顺序追读消息。
- 把选中的连续字节发送给消费者。

这天然适合 `FileChannel`。Kafka 设计文档强调的重点并不是“把 `.log` 映射进用户态内存”，而是“把顺序 I/O 交给 page cache，并尽量用 `sendfile` 发送到 socket”。

### 4.2 `mmap` 会削弱 `.log` 读路径上的零拷贝优势

这点最关键，也是最容易被误解的地方。Kafka 当前 `.log` 的主发送路径里，broker 尽量保留“文件语义”：

- `FileRecords.writeTo(...)` 持有底层 `FileChannel`
- 再通过 `TransferableChannel.transferFrom(...)`
- 非 SSL 场景下最终尽量委托到 `FileChannel.transferTo(...)`

这条路径向内核表达的是：

```text
把这个文件 fd 的某个 offset 开始的 count 个字节发送到这个 socket fd
```

这很接近：

```text
file fd -> socket fd
```

而如果 `.log` 主要通过 `mmap` 来读，应用往往就会先拿到：

```text
MappedByteBuffer / 用户态可访问内存
```

然后再走：

```text
SocketChannel.write(buffer)
```

这时内核看到的语义更像：

```text
user memory -> socket fd
```

于是最关键的“文件 fd 到 socket fd 的内核直通路径”就弱掉了。也就是说：

- `mmap` 不一定会多出一份 JVM 堆内副本。
- 但应用线程已经在用户态触碰了这些字节。
- 一旦发送路径变成 `write(buffer)`，通常就不再是 Kafka 现在依赖的 `sendfile` 快路径。

所以更准确的结论不是“`mmap` 完全不能和 `sendfile` 共存”，而是：

```text
如果发送时手里拿的是文件语义，更容易走 sendfile；
如果发送时手里拿的是用户态内存语义，通常就更像普通 write(buffer)。
```

### 4.3 `.log` 文件大且多，`mmap` 管理成本更高

索引文件本来就小，映射成本相对可控。`.log` 则不同：

- segment 多。
- 单个文件大。
- 生命周期长，且会滚动、恢复、删除、截断。

Kafka 官方运维文档已经提醒要关注 `vm.max_map_count`。如果把大量 `.log` segment 也一起映射，映射区域数量和管理复杂度都会进一步上升。

### 4.4 JVM 上 `mmap` 的生命周期控制并不轻松

Kafka 对索引文件都专门处理了 unmap、resize、truncate 一类问题，因为映射文件的释放时机并不像普通文件句柄那样直观。把更大的 `.log` 文件也放到这条路径上，只会让恢复、删除、段切换和异常处理更复杂。

## 5. 为什么说 `mmap` 会削弱 `.log` 读路径的零拷贝优势

这个问题可以单独拆开看。

### 5.1 `sendfile` 依赖的是“文件 fd 到 socket fd”

`sendfile` 的价值，不只是“少拷贝一次”，更重要的是：

- 应用不必先把文件内容显式读到用户态。
- 内核可以直接基于文件描述符、offset 和 length，把 page cache 中的数据送到 socket。

这就是 Kafka 当前 `.log` 发送主路径最想保住的能力。

### 5.2 `mmap` 解决的是“把文件内容映射成应用可访问的内存”

`mmap` 的强项是：

- 不用每次手工 `read()`。
- 让应用像读内存一样读文件。
- 适合少量随机访问或需要解析小块元数据的场景。

但它不会自动等价于 `sendfile`。一旦应用开始读取 `MappedByteBuffer`，并把这段内存写到 socket，发送路径就更像普通“用户态 buffer -> socket”。

### 5.3 三条路径的差别

普通 `read + write`：

```text
磁盘
  ↓
page cache（内核）
  ↓ copy_to_user
用户态 buffer
  ↓ write()
socket buffer / 网络栈（内核）
  ↓
网卡
```

`mmap + write`：

```text
磁盘
  ↓
page cache（内核）
  ↓ 映射到进程虚拟地址空间
MappedByteBuffer / 用户态可访问内存
  ↓ write()
socket buffer / 网络栈（内核）
  ↓
网卡
```

`sendfile / transferTo`：

```text
磁盘
  ↓
page cache（内核）
  ↓ sendfile / transferTo
socket / 网络栈（内核）
  ↓
网卡
```

从这个角度看，`mmap` 主要减少的是“显式读入独立用户缓冲区”的成本；而 `sendfile` 主要减少的是“文件内容先经过用户态再发网络”的成本。它们解决的是两类不同问题。

## 6. Kafka 一次 `fetch` 的典型链路

把一次典型的 `fetch` 请求拆开看，Kafka 的流程可以简化成下面 8 步：

1. 消费者发来 `Fetch(offset/maxBytes)` 请求。
2. broker 先定位到对应 partition 的 `.log` segment。
3. 如果是按 offset 取数，broker 先查 `.index`；如果是按时间取数，则先查 `.timeindex`。
4. 这一步查索引时，Kafka 主要是在 mmap 过的索引文件上做二分查找和少量顺序扫描。
5. broker 拿到的是 `.log` 中的一个近似物理位置，然后再从这里顺着 `.log` 找到真正要返回的 batch 起点。
6. 得到起始位置和返回长度后，broker 不需要先把整段 payload 解析到用户态。
7. `FileRecords.writeTo(...)` 通过 `TransferableChannel.transferFrom(fileChannel, position, count)` 把文件区间交给网络层。
8. 在非 SSL 主路径上，这一步尽量继续走 `FileChannel.transferTo(...)` / `sendfile`，把 page cache 中的字节直接送到 socket。

可以把这条链路画成：

```text
fetch request
   -> 查 segment
   -> mmap 查 .index / .timeindex
   -> 得到 .log 物理位置
   -> 顺着 .log 找到精确 batch
   -> FileRecords.writeTo(...)
   -> transferTo / sendfile
   -> socket
```

这条链路正好说明了两种机制的分工：

- `mmap` 用来“找数据”。
- `sendfile` 用来“搬数据”。

## 7. `mmap` 和 `sendfile` 不是一回事

很多文章会说 Kafka 同时用了两种 zero-copy：`mmap` 和 `sendfile`。这句话不是完全错，但很容易把两个层次混在一起。

更准确地说：

- `mmap` 更接近“文件访问侧的少拷贝”。
- `sendfile` 更接近“网络发送侧的少拷贝/零拷贝”。

### 7.1 `mmap` 更像“避免显式拷到独立用户缓冲区”

传统 `read()` 更像：

```text
page cache -> 用户态 byte[] / ByteBuffer
```

而 `mmap` 更像：

```text
page cache -> 映射为进程可访问地址空间
```

应用读的是映射后的地址，而不是先 `read()` 出来的一份独立副本。

### 7.2 `sendfile` 更像“避免经过用户态参与发送”

传统发网络更像：

```text
page cache -> 用户态 buffer -> socket
```

而 `sendfile` 更像：

```text
page cache -> socket
```

所以它们不冲突，但也不替代：

- 查索引时，应用必须读少量元数据，`mmap` 合适。
- 发日志正文时，应用最好不要碰整块 payload，`sendfile` 合适。

## 8. `read`、`mmap`、`sendfile` 对比表

| 路径 | 应用是否需要先读到用户态 | 是否保留文件语义 | 更适合的场景 |
| --- | --- | --- | --- |
| `read + write` | 需要 | 弱 | 通用路径，简单直接 |
| `mmap + write` | 需要以“内存语义”访问 | 弱 | 小量随机访问、索引、解析 |
| `sendfile / transferTo` | 不需要先拿到 payload | 强 | 大块顺序发送、文件到网络 |

如果压缩成一句最实用的判断，就是：

```text
mmap 更适合“找数据”，sendfile 更适合“搬数据”。
```

## 9. SSL/TLS 是一个重要例外

前面的“零拷贝优势”主要针对 Kafka 能自然走 `sendfile` 的非 SSL 主路径。

如果 broker 到客户端开启了 SSL/TLS，那么即使 `.log` 继续使用 `FileChannel`，发送时也常常需要在用户态参与加密，`sendfile` 这条文件到网络的快路径就会被打断。

所以这里最准确的说法是：

- 非 SSL 主路径上，Kafka 很依赖 `page cache + sendfile` 的收益。
- SSL/TLS 场景下，这条收益会明显收缩。

## 10. 一个适合记忆的总结

可以把这篇文档压缩成四句话：

```text
`.log` 和 `.index/.timeindex` 都依赖 page cache。
`.index/.timeindex` 适合 mmap，因为它们要做的是少量随机定位。
`.log` 不一定适合 mmap，因为它的主任务是顺序追加和顺序发送。
Kafka 更想用 mmap 去“找数据”，用 sendfile 去“搬数据”。
```

## 11. 参考资料

- Kafka Design 4.3: <https://kafka.apache.org/43/design/design/>
- Kafka Hardware and OS 4.3: <https://kafka.apache.org/43/operations/hardware-and-os/>
- `FileChannel` Javadoc: <https://docs.oracle.com/javase/8/docs/api/java/nio/channels/FileChannel.html>
- Linux `sendfile(2)`: <https://man7.org/linux/man-pages/man2/sendfile.2.html>
- `TransferableChannel.java`: <https://github.com/apache/kafka/blob/trunk/clients/src/main/java/org/apache/kafka/common/network/TransferableChannel.java>
- `PlaintextTransportLayer.java`: <https://github.com/apache/kafka/blob/trunk/clients/src/main/java/org/apache/kafka/common/network/PlaintextTransportLayer.java>
- `FileRecords.java`: <https://github.com/apache/kafka/blob/trunk/clients/src/main/java/org/apache/kafka/common/record/FileRecords.java>
- `AbstractIndex.java`: <https://github.com/apache/kafka/blob/trunk/storage/src/main/java/org/apache/kafka/storage/internals/log/AbstractIndex.java>
- `OffsetIndex.java`: <https://github.com/apache/kafka/blob/trunk/storage/src/main/java/org/apache/kafka/storage/internals/log/OffsetIndex.java>
- `TimeIndex.java`: <https://github.com/apache/kafka/blob/trunk/storage/src/main/java/org/apache/kafka/storage/internals/log/TimeIndex.java>
