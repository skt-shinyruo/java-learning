# Zero Copy：复制路径和常见类型

本文档关注 zero-copy 本身，不是 Java NIO API 教程。Java 的 `ByteBuffer`、`SocketChannel`、`FileChannel` 和 Netty `ByteBuf` 只作为例子出现，用来说明不同技术到底省掉了哪一次复制。

如果只先记一句话：

- zero-copy 不是“完全没有数据搬运”，而是明确数据经过应用层、运行时、用户态、内核态、设备时发生了哪些复制，再尽量去掉不必要的复制。

---

## 1. Zero Copy 到底讨论什么

zero-copy 讨论的核心问题不是“有没有某个 API”，而是：

```text
数据从哪里来？
数据到哪里去？
中间经过哪些 buffer？
哪一次复制可以省掉？
```

常见复制边界包括：

| 复制边界 | 例子 |
| --- | --- |
| 应用层内部 | `byte[] -> byte[]`、`header + body -> merged byte[]` |
| 运行时边界 | `Java heap -> JVM 临时 direct/native buffer` |
| 用户态和内核态 | `用户态 buffer -> 内核 socket buffer` |
| 内核对象之间 | `socket -> pipe -> socket` |
| 内核和设备之间 | DMA、NIC、磁盘控制器参与的数据搬运 |

因此判断一个技术是不是 zero-copy，或者属于哪一层 zero-copy，不要先看名字，而要先画路径：

```text
优化前：A -> B -> C -> D
优化后：A -> C -> D
```

然后回答：

```text
B 是什么？
A -> B 或 B -> C 的复制是否真的被省掉？
```

这也是本文档后续所有小节的组织方式：先讲复制路径，再讲技术名字。

---

## 2. 普通 I/O 的基础复制路径

### 2.1 什么是内核缓冲区

“内核缓冲区”不是一个固定对象，而是内核管理的、用于缓存、排队和组织 I/O 数据的内存及数据结构的统称。应用程序不能直接访问它们，只能通过 `read`、`write`、`send`、`recv` 等系统调用交换数据。

常见对应关系如下：

| I/O 对象 | 主要内核缓冲区 | 作用 |
| --- | --- | --- |
| 普通文件 | page cache | 缓存文件页，并把脏页异步写回存储设备 |
| socket | receive buffer、send buffer | 暂存应用尚未读取或内核尚未发送的数据 |
| 网卡 | DMA ring、descriptor 队列 | 让网卡和驱动协调收发数据所在的内存页 |

因此，“文件的内核缓冲区就是 page cache，socket 的内核缓冲区就是 socket buffer”可以作为入门理解；实际路径还会经过 TCP/IP 协议栈、驱动队列等其他内核对象。

用户态 buffer 则是应用或运行时持有的内存，例如 Java 的 `byte[]`、`ByteBuffer` 和 Netty `ByteBuf`。`ByteBuffer.allocateDirect()` 的数据虽在 JVM 堆外，仍属于用户态内存，不是内核缓冲区。

### 2.2 普通 socket read 路径

普通 TCP 接收数据时，可以简化成：

```text
网卡
    ↓ DMA
内核网络缓冲区
    ↓ TCP/IP 协议栈处理
内核 socket receive buffer
    ↓ copy_to_user
用户态 buffer
```

这里的用户态 buffer，就是应用程序传给 `read()` 的那块内存。在 Java 里可能是：

- `byte[]`
- `HeapByteBuffer`
- `DirectByteBuffer`
- Netty `ByteBuf`

内核 socket receive buffer 属于内核空间，应用程序不能直接读写。普通 socket read 的关键复制是：

```text
内核 socket receive buffer -> 用户态 buffer
```

这通常对应内核里的 `copy_to_user` 路径。

### 2.3 普通 socket write 路径

普通 TCP 发送数据时，可以简化成：

```text
用户态 buffer
    ↓ copy_from_user
内核 socket send buffer
    ↓ TCP/IP 协议栈处理
内核网络缓冲区
    ↓ DMA
网卡
```

普通 socket write 的关键复制是：

```text
用户态 buffer -> 内核 socket send buffer
```

这通常对应内核里的 `copy_from_user` 路径。

### 2.4 DMA 在这里的含义

DMA 不是“数据不移动”。DMA 的意思是设备可以直接和内存搬运数据，不需要 CPU 用 `memcpy` 一样的方式亲自复制每个字节。

所以更准确的说法是：

```text
zero-copy 通常减少 CPU 参与的内存复制；
数据仍然会在内存、内核对象、设备之间移动。
```

---

## 3. 应用层和运行时层面的 Zero Copy

### 3.1 `slice()` / `duplicate()`

应用层 zero-copy 最常见的形式是不复制 payload，而是创建一个共享底层内存的视图。

假设收到的数据格式是：

```text
[4 字节长度][payload]
```

低效写法通常会复制出 payload：

```java
byte[] packet = readFromSocket();
int len = parseLength(packet);

byte[] payload = new byte[len];
System.arraycopy(packet, 4, payload, 0, len);
```

使用 `ByteBuffer.slice()` 时，可以只创建视图：

```java
ByteBuffer buffer = ByteBuffer.allocateDirect(4096);

source.read(buffer);
buffer.flip();

int len = buffer.getInt();

ByteBuffer payload = buffer.slice();
payload.limit(len);
```

路径变化是：

```text
优化前：原始 buffer -> 新 payload byte[]
优化后：原始 buffer -> payload 视图
```

省掉的是应用层复制：

```text
byte[] -> byte[]
```

### 3.2 gathering write / `writev`

如果响应由 header 和 body 组成，低效写法会先合并：

```java
byte[] response = new byte[header.length + body.length];
System.arraycopy(header, 0, response, 0, header.length);
System.arraycopy(body, 0, response, header.length, body.length);
socket.write(response);
```

使用 gathering write 时，可以保持多个 buffer 分开：

```java
socketChannel.write(new ByteBuffer[] {
    headerBuffer,
    bodyBuffer
});
```

底层通常可以对应 `writev` 一类向量 I/O 能力：

```text
优化前：header + body -> merged byte[] -> write
优化后：header buffer + body buffer -> writev
```

省掉的是：

```text
多个 buffer -> 合并大 buffer
```

这仍然不等于消灭用户态到内核态的 socket 复制；它解决的是应用层合并复制。

### 3.3 `DirectByteBuffer`

`DirectByteBuffer` 解决的是 Java heap 和 JVM/native 临时 buffer 之间的中间复制。

Java 里 `ByteBuffer` 有两种常见分配方式：

```java
ByteBuffer heapBuffer = ByteBuffer.allocate(1024);
ByteBuffer directBuffer = ByteBuffer.allocateDirect(1024);
```

区别是：

- `ByteBuffer.allocate()`：数据在 Java heap 的 `byte[]` 里。
- `ByteBuffer.allocateDirect()`：数据在 JVM 堆外的 native memory 里。

注意：堆上仍然会有一个 `DirectByteBuffer` 对象，它保存容量、位置、限制、堆外内存地址等元数据；真正的数据不在 Java heap 的 `byte[]` 里。

heap buffer 写路径可能是：

```text
Java heap byte[]
    ↓ 复制一次
JVM 临时 direct/native buffer
    ↓ write() 系统调用
内核 socket/file buffer
```

direct buffer 写路径可以变成：

```text
Direct/native buffer
    ↓ write() 系统调用
内核 socket/file buffer
```

读路径也类似。

heap buffer 读：

```text
内核 socket/file buffer
    ↓ read() 系统调用
JVM 临时 direct/native buffer
    ↓ 复制一次
Java heap byte[]
```

direct buffer 读：

```text
内核 socket/file buffer
    ↓ read() 系统调用
Direct/native buffer
```

所以 `DirectByteBuffer` 省掉的是：

```text
Java heap byte[] <-> JVM 临时 direct/native buffer
```

它通常没有省掉：

```text
内核空间 <-> 用户空间
```

本模块带有配套 demo：

```bash
mvn -pl nio test
mvn -pl nio -DskipTests package
java -cp nio/target/classes yier.bubu.nio.NioDirectMemoryApp copy-path 1024
```

相关代码：

- `nio/src/main/java/yier/bubu/nio/DirectBufferCopyPathDemo.java`
- `nio/src/test/java/yier/bubu/nio/DirectBufferCopyPathDemoTest.java`

### 3.4 Selector 和 zero-copy 的关系

如果放到 Java NIO 里，`Selector` 本身不搬数据。它只负责告诉程序哪个 channel 可读、可写。

```text
Selector 解决：线程怎么等 I/O 就绪
zero-copy 解决：数据怎么少复制
```

真正和 zero-copy 相关的是 `Buffer`、`Channel`、系统调用、内核缓冲区和硬件 DMA。

---

## 4. 文件和内存映射相关的 Zero Copy

### 4.1 page cache

文件 I/O 通常会涉及 page cache。简化理解：

```text
磁盘
    ↓ DMA
page cache
```

普通 `read()` 读取文件时，常见路径是：

```text
page cache
    ↓ copy_to_user
用户态 buffer
```

也就是说，文件内容已经在内核 page cache 里，但普通 read 仍然会把数据复制到用户态 buffer。

写文件时，常见方向相反：

```text
用户态 buffer
    ↓ copy_from_user
page cache（脏页）
    ↓ 异步回写
磁盘
```

这里的 page cache 是文件 I/O 中最主要的内核缓存，但不是所有文件访问都必经它；例如使用 `O_DIRECT` 时，内核会尽量绕过 page cache。

### 4.2 `mmap` / `MappedByteBuffer`

`mmap` 的核心思路是把文件的 page cache 映射到用户进程地址空间。

普通 read：

```text
page cache -> 用户态 buffer
```

`mmap`：

```text
page cache
    ↓ 映射
用户进程地址空间
```

它省掉的是显式 read 路径中的复制：

```text
page cache -> 用户态 read buffer
```

Java 中对应的例子是 `MappedByteBuffer`：

```java
MappedByteBuffer buffer = fileChannel.map(
    FileChannel.MapMode.READ_ONLY,
    0,
    fileChannel.size()
);
```

本模块带有最小示例：

```bash
mvn -pl nio -DskipTests package
java -cp nio/target/classes yier.bubu.nio.NioDirectMemoryApp mmap
```

相关代码：

- `nio/src/main/java/yier/bubu/nio/MappedFileDemo.java`
- `nio/src/test/java/yier/bubu/nio/MappedFileDemoTest.java`

### 4.3 `sendfile` / `FileChannel.transferTo()`

文件到网络发送时，普通路径可能是：

```text
page cache
    ↓ copy_to_user
用户态 buffer
    ↓ copy_from_user
内核 socket send buffer
    ↓
网卡
```

`sendfile` 的目标是让数据不经过用户态 buffer：

```text
page cache
    ↓
socket / NIC
```

Java 中常见入口是：

```java
fileChannel.transferTo(position, count, socketChannel);
```

它省掉的是：

```text
文件 -> 用户态 -> socket
```

更具体地说，是避免文件数据先复制到用户态，再从用户态复制回内核 socket 路径。

调用方没有创建或传入 `ByteBuffer`，不代表底层绝对不存在用户态临时 buffer。操作系统、文件系统或目标 channel 不支持 `sendfile` 快路径时，JDK 可能回退到基于 `read` / `write` 的实现；但在支持的文件到 socket 场景，文件 payload 不会进入 Java 应用可见的 `byte[]` 或 `ByteBuffer`。

如果数据需要 TLS 加密、gzip 压缩、业务改写，文件到 socket 的 zero-copy 路径通常会被打断，因为应用层必须读取或变换数据。

---

## 5. 内核对象间和网络发送 Zero Copy

### 5.1 `splice` / `tee`

Linux 的 `splice()` 可以在内核对象之间移动数据，例如：

```text
socket A -> pipe -> socket B
```

简化路径是：

```text
内核 socket receive buffer
    ↓
pipe buffer
    ↓
内核 socket send buffer
```

这里用户进程只负责调用系统调用搬运引用，不真正读取 payload。

它省掉的是：

```text
socket/file -> 用户态 -> socket/file
```

`tee()` 可以在 pipe buffer 层复制引用，用于把同一份数据流复制到多个 pipe。它的关键也不是应用层复制字节，而是操作内核中的 buffer 引用。

这类机制适合解释透明转发路径，但如果应用必须解析、修改业务协议，数据通常还是要进入用户态。

### 5.2 `MSG_ZEROCOPY`

普通 socket write 会把用户态 buffer 复制到内核 socket send buffer：

```text
用户态 buffer
    ↓ copy_from_user
内核 socket send buffer
```

`MSG_ZEROCOPY` 的目标是减少这次复制。它会让内核引用用户态页，并在数据发送完成后通知应用。

简化路径是：

```text
用户态 pages
    ↓ page pinning
内核 / NIC 发送路径
    ↓ completion notification
应用才可以安全复用 buffer
```

它引入的关键问题是生命周期：

```text
内核或网卡还没发送完时，用户态 buffer 不能被提前复用或释放。
```

### 5.3 `io_uring send_zc`

`io_uring` 提供更现代的异步 I/O 机制，其中也有 zero-copy send 相关能力。它和 `MSG_ZEROCOPY` 类似，都要处理 buffer 生命周期和发送完成通知。

这类能力属于更底层的 Linux 网络优化，不是普通 Java socket API 的常规能力。

---

## 6. 设备级和内核绕过

### 6.1 DMA 和 scatter-gather DMA

DMA 让设备直接和内存搬运数据，减少 CPU 亲自复制。

scatter-gather DMA 可以让设备从多个不连续的内存片段读取或写入数据。它和 `writev` 的思想类似：不一定先把多个片段合并成一块连续大 buffer。

简化理解：

```text
buffer A
buffer B
buffer C
    ↓ scatter-gather DMA
NIC
```

### 6.2 NIC ring buffer

网卡收发包通常会涉及 ring buffer / descriptor。驱动和网卡通过描述符交换“数据在哪块内存里”的信息。

这说明 zero-copy 的深层优化经常不是复制字节，而是传递 page、buffer、descriptor 的引用。

### 6.3 AF_XDP、DPDK、RDMA

更激进的 zero-copy 或低延迟方案会绕过传统内核网络栈的一部分：

- AF_XDP：让用户态程序更直接地处理网卡收发队列。
- DPDK：用户态网络栈，绕过传统内核协议栈路径。
- RDMA：让远端机器直接读写本机注册内存区域。

这些技术能进一步减少复制和内核路径开销，但复杂度、部署要求、内存管理成本都明显更高。

---

## 7. 常见 Zero Copy 类型总表

通常所说的 zero-copy 可以按下面几类理解：

| 类型 | 典型技术 | 省掉的复制 |
| --- | --- | --- |
| 应用层 zero-copy | `slice()`、`duplicate()`、Netty `ByteBuf.slice()` | 避免 `byte[] -> byte[]` |
| 组合写 zero-copy | `writev`、`SocketChannel.write(ByteBuffer[])` | 避免把多个 buffer 先合并成一个大 buffer |
| JVM 层少拷贝 | `DirectByteBuffer` | 避免 `Java heap -> 临时 native buffer` |
| 内存映射 | `mmap`、`MappedByteBuffer` | 避免 `read()` 把文件内容复制到用户态 buffer |
| 文件到网络 zero-copy | `sendfile`、`FileChannel.transferTo()` | 避免 `文件 -> 用户态 -> socket` |
| 内核对象间 zero-copy | `splice`、`tee` | 避免 `socket/file -> 用户态 -> socket/file` |
| 用户内存 zero-copy send | `MSG_ZEROCOPY`、`io_uring send_zc` | 避免 `用户态 buffer -> 内核 socket buffer` |
| 内核绕过 / 设备级 zero-copy | DPDK、AF_XDP、RDMA | 绕过或减少传统内核网络栈的数据复制 |

这张表的重点不是背 API，而是把每个技术和“省掉哪一次复制”绑定起来。

---

## 8. 性能收益来自哪里

zero-copy 的性能收益通常来自多个方面。

### 8.1 减少 CPU 拷贝

少做 `memcpy`、`copy_to_user`、`copy_from_user`，CPU 就可以少花时间搬字节。

### 8.2 减少内存带宽消耗

大块数据反复复制会吃掉内存带宽。zero-copy 减少重复搬运后，内存总线压力会下降。

### 8.3 减少 CPU cache 污染

复制大块一次性数据会把它们带进 cache，挤掉业务热点数据。减少复制可以降低这种 cache 污染。

### 8.4 减少系统调用路径开销

某些机制可以把原来的 `read()` + `write()` 路径压缩成更少的系统调用，例如文件到 socket 的 `sendfile` 路径。

### 8.5 减少对象分配和 GC 压力

应用层 zero-copy、buffer pool、direct buffer 复用都能减少临时数组和临时对象分配。

不要把这些收益理解成固定倍数。实际效果取决于数据大小、协议处理、内核版本、TLS、网卡能力、buffer 生命周期和业务是否需要修改数据。

---

## 9. 生命周期和所有权

zero-copy 经常不是复制数据，而是共享同一块内存、同一个 page 或同一个 buffer 引用。共享会带来生命周期问题。

### 9.1 `slice()` 共享底层内存

`slice()` 不复制数据，所以原始 buffer 被复用、覆盖、释放时，slice 看到的数据也会受影响。

```text
原始 buffer
    ├── slice A
    └── slice B
```

这要求程序明确：谁拥有底层内存，什么时候可以复用。

### 9.2 Netty `ByteBuf` 引用计数

Netty 的 `ByteBuf` 支持引用计数。zero-copy 转发时，如果同一块内存被多个 handler 或 channel 持有，通常需要正确处理 `retain()` / `release()`。

问题不是数据复制，而是：

```text
不能提前释放；
也不能忘记释放。
```

### 9.3 `DirectByteBuffer` 释放

Direct buffer 的真实数据在堆外。释放通常依赖 `DirectByteBuffer` 对象被 GC 后触发 Cleaner 回收，不等于 Java 引用消失后立即归还内存。

需要注意：

- 分配和释放 direct buffer 的成本通常高于普通堆内 buffer。
- 高频创建 direct buffer 容易带来 native memory 压力。
- 上限可通过 `-XX:MaxDirectMemorySize` 控制。
- 耗尽时可能抛出 `java.lang.OutOfMemoryError: Direct buffer memory`。

实践上，网络 I/O、大文件 I/O、反复复用的缓冲区可以考虑 direct buffer 或框架提供的池化 buffer；少量、短生命周期、小数据则不一定值得使用 direct buffer。

### 9.4 `mmap` 生命周期

`mmap` 把文件映射到进程地址空间，但映射关系、文件变化、unmap 时机和平台差异都可能影响行为。

映射不是普通 Java heap 对象，它的生命周期管理更接近操作系统资源管理。

### 9.5 `MSG_ZEROCOPY` completion

`MSG_ZEROCOPY` 需要发送完成通知。应用不能在内核或网卡还没完成发送时就复用用户态 buffer。

这类技术把复制成本换成了更复杂的所有权和完成通知管理。

---

## 10. 常见误区

### 10.1 zero-copy 就是一次复制都没有

不准确。

更准确的说法是：

```text
zero-copy 是减少特定边界上的 CPU 拷贝，不是数据完全不移动。
```

### 10.2 `DirectByteBuffer` 就是完整 zero-copy

不准确。

`DirectByteBuffer` 主要减少：

```text
Java heap <-> JVM 临时 direct/native buffer
```

普通 socket read/write 通常仍然有：

```text
内核空间 <-> 用户空间
```

### 10.3 Selector 和 zero-copy 是一回事

不准确。

```text
Selector：解决大量连接怎么等待 I/O 就绪。
zero-copy：解决数据怎么少复制。
```

### 10.4 `sendfile` / `transferTo()` 适合所有网络响应

不准确。

它们适合文件到 socket 的路径。如果响应需要 TLS 加密、gzip 压缩、业务改写或应用层解析，zero-copy 路径可能被打断。

### 10.5 用了 zero-copy 一定更快

不准确。

小数据、短生命周期 buffer、业务 CPU 重、需要解析修改数据、生命周期管理复杂时，zero-copy 的收益可能不明显，甚至会让系统更难维护。

最终还是回到同一个判断：

```text
先画数据路径，再判断省掉了哪一次复制，以及为此引入了什么代价。
```
