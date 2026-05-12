# Java NIO：Direct Memory 和中间复制

这篇文档聚焦一个问题：

- Java NIO 为什么经常和 Direct Memory 放在一起讲，以及所谓“减少中间复制”到底减少了哪一次复制

如果只先记一句话：

- `ByteBuffer.allocateDirect()` 会分配堆外 buffer，NIO 的 `Channel` 可以把这块 native memory 交给底层 I/O 使用，从而减少 `Java heap byte[] <-> JVM 临时 native buffer` 这一次中间复制

---

## 1. NIO 怎么使用 Direct Memory

Java NIO 的核心抽象是：

- `Buffer`：数据缓冲区，例如 `ByteBuffer`
- `Channel`：I/O 通道，例如 `SocketChannel`、`FileChannel`
- `Selector`：多路复用器，用于非阻塞网络 I/O

`ByteBuffer` 有两种常见分配方式：

```java
ByteBuffer heapBuffer = ByteBuffer.allocate(1024);
ByteBuffer directBuffer = ByteBuffer.allocateDirect(1024);
```

区别是：

- `ByteBuffer.allocate()`：数据在 Java 堆里的 `byte[]` 中
- `ByteBuffer.allocateDirect()`：数据在 JVM 堆外的 native memory 中

注意：堆上仍然会有一个 `DirectByteBuffer` 对象，它保存容量、位置、限制、堆外内存地址等元数据；真正的数据不在 Java heap 的 `byte[]` 里。

可以运行配套 demo 看这两个 buffer 的差异：

```bash
mvn -pl nio test
mvn -pl nio -DskipTests package
java -cp nio/target/classes yier.bubu.nio.NioDirectMemoryApp copy-path 1024
```

相关代码：

- `nio/src/main/java/yier/bubu/nio/DirectBufferCopyPathDemo.java`
- `nio/src/test/java/yier/bubu/nio/DirectBufferCopyPathDemoTest.java`

---

## 2. “减少中间复制”减少的是哪次复制

以 `SocketChannel.write()` 或 `FileChannel.write()` 为例。

如果用普通堆内 `ByteBuffer`：

```text
Java heap byte[]
    ↓ 复制一次
临时 direct/native buffer
    ↓ write() 系统调用
内核 socket/file buffer
    ↓
网卡或磁盘
```

因为普通 `ByteBuffer` 背后是 Java 堆内的 `byte[]`。GC 可能移动堆对象，而底层系统调用需要相对稳定的 native 内存地址，所以 JVM/NIO 实现通常需要先把数据复制到临时 direct/native buffer，再交给系统调用。

如果用 direct `ByteBuffer`：

```text
Direct/native buffer
    ↓ write() 系统调用
内核 socket/file buffer
    ↓
网卡或磁盘
```

少掉的是：

```text
Java heap byte[] -> JVM 临时 direct/native buffer
```

读数据时也类似。

堆内 buffer 读：

```text
网卡或磁盘
    ↓
内核 socket/file buffer
    ↓ read() 系统调用
临时 direct/native buffer
    ↓ 复制一次
Java heap byte[]
```

direct buffer 读：

```text
网卡或磁盘
    ↓
内核 socket/file buffer
    ↓ read() 系统调用
Direct/native buffer
```

所以这里说的“减少复制”，不是说完全没有复制。普通 `read()` / `write()` 通常仍然存在：

```text
内核空间 <-> 用户空间
```

Direct Memory 主要减少的是：

```text
Java heap byte[] <-> JVM 临时 direct/native buffer
```

---

## 3. 和 zero-copy 的关系

Direct Memory 不等于完整意义上的 zero-copy。

它主要解决的是 Java 堆内数组和 JVM/native 临时缓冲区之间的复制。要进一步减少内核态和用户态之间的数据搬运，通常会涉及：

- `FileChannel.map()` / `MappedByteBuffer`：内存映射文件，底层通常对应操作系统的 `mmap`
- `FileChannel.transferTo()` / `transferFrom()`：可能利用操作系统的 `sendfile` 等能力
- 网络框架中的 buffer pool：减少 direct buffer 的频繁分配和释放

本模块带了一个最小 `MappedByteBuffer` 示例：

```bash
mvn -pl nio -DskipTests package
java -cp nio/target/classes yier.bubu.nio.NioDirectMemoryApp mmap
```

相关代码：

- `nio/src/main/java/yier/bubu/nio/MappedFileDemo.java`
- `nio/src/test/java/yier/bubu/nio/MappedFileDemoTest.java`

---

## 4. 使用 direct buffer 的注意点

Direct buffer 适合 I/O 密集、buffer 可复用的场景，但不是所有场景都应该直接替换成 `allocateDirect()`。

需要注意：

- 分配和释放 direct buffer 的成本通常高于普通堆内 buffer。
- 高频创建 direct buffer 容易带来 native memory 压力，实际项目里常配合 buffer pool。
- 上限可通过 `-XX:MaxDirectMemorySize` 控制。
- 释放通常依赖 `DirectByteBuffer` 对象被 GC 后触发 Cleaner 回收，不等于 Java 引用消失后立即归还内存。
- 耗尽时可能抛出 `java.lang.OutOfMemoryError: Direct buffer memory`。

实践上可以这样选：

- 少量、短生命周期、小数据：优先普通 heap buffer，简单且 GC 可见。
- 网络 I/O、大文件 I/O、反复复用的缓冲区：可以考虑 direct buffer 或框架提供的池化 buffer。
- 文件随机访问或大文件映射：可以学习 `FileChannel.map()`，但要注意映射生命周期和平台差异。
