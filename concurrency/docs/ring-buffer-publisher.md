# Ring Buffer Publisher：JVM 内广播、预分配槽位与背压

本文说明 `yier.bubu.concurrency.ringbuffer` 这一组类实现的 in-process ring-buffer publisher。它借鉴了高性能事件传递的常见思路：固定大小环形数组、按 sequence 发布、消费者按 sequence 顺序处理。

它解决的是：

- JVM 内线程之间的低分配事件广播
- 单生产者 / 多生产者下的有界发布
- 慢消费者导致的背压
- 不同等待策略下的延迟 / CPU 取舍

它**不**解决的是：

- 跨进程传输
- 持久化
- 消息重放
- ACK / 重试 / 死信
- 事务一致性
- 网络级交付保证

如果你需要的是 broker、日志系统或可恢复消息队列，这套实现并不是那个层面的东西。它只是 **JVM 本地内存中的 ring buffer publisher**。

对应代码位置：

- `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/RingBuffer.java`
- `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/SingleProducerSequencer.java`
- `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/MultiProducerSequencer.java`
- `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/BatchEventProcessor.java`

对应测试：

- `concurrency/src/test/java/yier/bubu/concurrency/ringbuffer/SingleProducerPublishTest.java`
- `concurrency/src/test/java/yier/bubu/concurrency/ringbuffer/MultiProducerSequencerTest.java`
- `concurrency/src/test/java/yier/bubu/concurrency/ringbuffer/BackpressureTest.java`
- `concurrency/src/test/java/yier/bubu/concurrency/ringbuffer/WaitStrategyAndProcessorTest.java`
- `concurrency/src/test/java/yier/bubu/concurrency/ringbuffer/ExceptionHandlingTest.java`

## 1. 它到底是什么

可以先把它理解成一个固定长度的环形事件槽位数组，加上一套 sequence 协议：

- 生产者先申请下一个 sequence
- 把数据写入该 sequence 对应的槽位
- 再把该 sequence 标记为已发布
- 消费者按 sequence 等待、读取、处理

这意味着它更像：

- 一个**有界内存队列**
- 一个**广播式事件总线**
- 一个**按 sequence 协调的线程间发布器**

而不是：

- Kafka 这样的持久化日志
- RabbitMQ 这样的 broker
- 带确认与重投的可靠消息中间件

这里的“发布成功”只表示：**事件已经写入当前 JVM 进程内的 ring buffer，并对本地消费者变得可见**。

## 2. Ring Buffer 布局：为什么一定要 2 的幂

`RingBuffer` 内部维护的是一个固定长度数组：

```java
private final Object[] entries;
private final int indexMask;
```

构造时会校验 `bufferSize` 必须是正的 2 的幂。原因是这样就可以用位运算代替取模：

```java
public T get(long sequence) {
    return (T) entries[(int) sequence & indexMask];
}
```

如果 `bufferSize = 8`，那么：

- `indexMask = 7`
- `sequence & 7` 的结果会落在 `0..7`

例如：

- `0 & 7 = 0`
- `7 & 7 = 7`
- `8 & 7 = 0`
- `15 & 7 = 7`

所以：

- sequence 单调递增
- 数组下标循环复用
- 不需要 `% bufferSize`

这就是 ring buffer 的核心布局：**逻辑上无限递增的 sequence，映射到物理上循环复用的固定数组槽位**。

## 3. 预分配事件槽位与 `EventTranslator`

这套实现不是每发布一次就 `new` 一个事件对象，而是在构造 `RingBuffer` 时一次性把所有槽位创建出来：

```java
for (int index = 0; index < entries.length; index++) {
    entries[index] = eventFactory.newInstance();
}
```

创建方式来自 `EventFactory<T>`。之后发布时，生产者不是把一个新对象塞进去，而是拿到已有槽位并原地填充：

```java
ringBuffer.publish(new EventTranslator<OrderEvent>() {
    @Override
    public void translateTo(OrderEvent event, long sequence) {
        event.setOrderId(1001L);
        event.setPrice(99);
    }
});
```

这就是 `EventTranslator<T>` 的作用：

- `RingBuffer` 先申请 sequence
- 根据 sequence 找到对应槽位
- 调用 translator 把数据写入该槽位
- 最后发布 sequence

`RingBuffer.publish(...)` 的关键路径可以概括成：

```java
long sequence = sequencer.next();
try {
    translator.translateTo(get(sequence), sequence);
} finally {
    sequencer.publish(sequence);
}
```

这里有两个要点：

- 事件对象是**复用**的，不要把槽位对象长期缓存到别处
- `publish` 在 `finally` 中执行，表示一旦拿到 sequence，就会把该 sequence 对外发布

因此 translator 应该只做“把当前事件槽位填好”这件事，不要写太重的逻辑。

## 4. Cursor、gating sequence 与慢消费者背压

### 4.1 `cursor` 是什么

`Sequencer` 内部有一个 `cursor`，表示当前已经可见的发布进度：

- 单生产者下，发布 `sequence` 后直接把 `cursor` 设为该值
- 多生产者下，`cursor` 只能推进到“从当前值开始连续已发布”的位置

消费者通常通过 `SequenceBarrier.waitFor(nextSequence)` 等待某个 sequence 可用。

### 4.2 `gating sequence` 是什么

生产者除了看自己的发布位置，还要看消费者有没有追上。消费者处理进度由 `Sequence` 表示，注册方式如下：

```java
BatchEventProcessor<OrderEvent> processor =
        new BatchEventProcessor<OrderEvent>(
                ringBuffer,
                ringBuffer.newBarrier(),
                handler,
                new LoggingExceptionHandler<OrderEvent>());

ringBuffer.addGatingSequences(processor.getSequence());
```

`gatingSequences` 的最小值表示“最慢消费者处理到哪里了”。生产者申请下一个 sequence 时，会检查：

- 如果继续前进会覆盖最慢消费者还没处理完的槽位，则不能发布
- 要么阻塞等待
- 要么 `tryPublish` 直接失败
- 要么带超时的 `publish(..., timeout, unit)` 返回 `false`

这就是背压来源：**不是队列无限长，而是慢消费者会卡住生产者，避免旧槽位被提前覆盖**。

### 4.3 一个直观例子

假设：

- `bufferSize = 2`
- 当前最慢消费者 sequence 是 `-1`

此时连续发布两个事件后，环已满。测试 `BackpressureTest` 验证了这件事：

```java
Assert.assertTrue(ringBuffer.tryPublish(new NoOpTranslator()));
Assert.assertTrue(ringBuffer.tryPublish(new NoOpTranslator()));
Assert.assertFalse(ringBuffer.tryPublish(new NoOpTranslator()));
```

等慢消费者前进后，生产者才能继续申请新的 sequence。

## 5. 单生产者与多生产者：差别不在“能不能发”，而在“cursor 怎么推进”

## 5.1 `SingleProducerSequencer`

单生产者版本的思路很直接：

- 只有一个线程递增 `nextValue`
- 申请 sequence 不需要 CAS
- 发布时直接：

```java
cursor.set(sequence);
waitStrategy.signalAllWhenBlocking();
```

因为不存在多个生产者抢占发布顺序，所以：

- 申请顺序就是发布顺序
- `cursor` 可以连续推进
- `getHighestPublishedSequence(...)` 直接返回 `availableSequence`

## 5.2 `MultiProducerSequencer`

多生产者版本要多处理两个问题：

1. 多线程如何安全 claim sequence
2. 某个较大的 sequence 先发布时，为什么不能立刻推进 `cursor`

claim sequence 用的是 CAS：

```java
if (nextValue.compareAndSet(current, nextSequence)) {
    return nextSequence;
}
```

但 claim 成功不等于可以立刻推进 `cursor`。因为可能出现：

- 线程 A claim 到 `0`
- 线程 B claim 到 `1`
- 线程 B 先发布 `1`
- 线程 A 还没发布 `0`

这时 sequence `1` 虽然“单点可用”，但从消费者视角看，`0` 还缺失，不能把 `cursor` 推到 `1`。否则消费者会误以为 `0..1` 都连续可读。

所以多生产者版本额外维护了：

- `availableBuffer`
- 每个槽位的 availability flag

发布时先把对应 sequence 标记为 available，再尝试从 `cursor + 1` 开始连续推进：

```java
private void advanceCursor() {
    long nextSequence = cursor.get() + 1L;
    long claimedSequence = nextValue.get();
    while (nextSequence <= claimedSequence && isAvailableUnsafe(nextSequence)) {
        cursor.set(nextSequence);
        nextSequence++;
    }
}
```

这段逻辑保证的是：

- 单个 sequence 可以先发布
- 但 `cursor` 只代表**连续区间**
- 有 gap 时先停住
- gap 被补齐后，再一次性推进

测试 `publish_shouldNotAdvanceCursorPastGap()` 就验证了这一点：

- 先发布 `1`，`cursor` 仍然是 `-1`
- 再发布 `0`，`cursor` 才推进到 `1`

## 6. Wait Strategy：用 CPU 换延迟

消费者等待 sequence 可用时，行为由 `WaitStrategy` 决定。这里实现了 4 种策略。

### 6.1 `BlockingWaitStrategy`

特点：

- 先用 `Condition.await()` 等待发布推进
- 被唤醒后再继续检查依赖 sequence
- CPU 占用最低
- 延迟通常最高

适合：

- 吞吐要求还可以
- 更关心线程资源
- 不希望空转太猛

### 6.2 `BusySpinWaitStrategy`

特点：

- 一直循环检查
- 几乎不主动让出 CPU
- 延迟最低
- CPU 消耗最高

适合：

- 线程数可控
- 对尾延迟非常敏感
- 愿意拿 CPU 换响应时间

### 6.3 `YieldingWaitStrategy`

特点：

- 先自旋一小段
- 再 `Thread.yield()`
- 在延迟与 CPU 之间做中间取舍

### 6.4 `SleepingWaitStrategy`

特点：

- 先自旋
- 再 yield
- 最后 `LockSupport.parkNanos(1L)`

它比纯 busy spin 更省 CPU，通常也比纯 blocking 更快恢复，但延迟稳定性取决于调度。

可以先把选择原则记成一句话：

- 更低延迟：`BusySpin` -> `Yielding` -> `Sleeping` -> `Blocking`
- 更低 CPU：`Blocking` -> `Sleeping` -> `Yielding` -> `BusySpin`

没有“绝对最好”的策略，只有符合当前负载与机器预算的策略。

## 7. `BatchEventProcessor`：广播消费，而不是竞争消费

`BatchEventProcessor<T>` 代表一个独立消费者，它持有自己的 `Sequence`：

```java
private final Sequence sequence = new Sequence(-1L);
```

运行逻辑大致是：

1. 等待 `nextSequence` 可用
2. 从 ring buffer 按 sequence 顺序取事件
3. 调用 `eventHandler.onEvent(event, sequence)`
4. 消费成功后推进自己的 `sequence`

启动方式例如：

```java
RingBuffer<OrderEvent> ringBuffer =
        RingBuffer.createSingleProducer(OrderEvent::new, 1024, new BlockingWaitStrategy());

BatchEventProcessor<OrderEvent> first =
        new BatchEventProcessor<OrderEvent>(
                ringBuffer,
                ringBuffer.newBarrier(),
                firstHandler,
                new LoggingExceptionHandler<OrderEvent>());

BatchEventProcessor<OrderEvent> second =
        new BatchEventProcessor<OrderEvent>(
                ringBuffer,
                ringBuffer.newBarrier(),
                secondHandler,
                new LoggingExceptionHandler<OrderEvent>());

ringBuffer.addGatingSequences(first.getSequence(), second.getSequence());

first.start();
second.start();
```

这里的两个 processor 都会看到同一批事件。测试 `batchEventProcessor_shouldBroadcastEventsToMultipleConsumers()` 也验证了：

- 第一个消费者收到 `1, 2, 3`
- 第二个消费者也收到 `1, 2, 3`

所以这里是**广播消费**：

- 不是多个消费者竞争同一条消息“只能一个人拿到”
- 而是每个 processor 都沿着自己的 sequence 独立前进

同时也意味着：最慢的那个 gating sequence 会影响生产者是否还能继续覆盖旧槽位。

## 8. 异常处理：记录后继续，或直接失败

`BatchEventProcessor` 在处理单个事件时会捕获 `eventHandler` 抛出的异常，并交给 `ExceptionHandler`：

```java
try {
    eventHandler.onEvent(event, nextSequence);
    sequence.set(nextSequence);
    nextSequence++;
} catch (Throwable exception) {
    exceptionHandler.handleEventException(exception, nextSequence, event);
    sequence.set(nextSequence);
    nextSequence++;
}
```

当前实现给了两种策略。

### 8.1 `LoggingExceptionHandler`

行为：

- 打印异常到 `System.err`
- 当前 sequence 仍然视为已处理
- processor 继续处理后续事件

这适合：

- 教学演示
- 容忍单条事件失败
- 更希望流水继续往前

测试 `loggingExceptionHandler_shouldLetProcessorAdvanceAfterHandlerFailure()` 证明了：

- sequence `0` 失败
- sequence `1` 仍然会继续处理
- processor 的 sequence 最终推进到 `1`

### 8.2 `FatalExceptionHandler`

行为：

- 直接抛出 `IllegalStateException`
- 处理线程退出
- 失败的 sequence 不会被推进
- processor 的 sequence 会从 ring buffer 的 gating sequences 中移除

测试 `fatalExceptionHandler_shouldStopProcessorAndReleaseProducerGating()` 证明了：

- 处理 `0` 失败后 processor 停止
- processor sequence 仍然停留在 `-1`
- 后续生产者不会因为这个已终止 processor 永久背压

这更接近 fail-fast：一旦业务处理逻辑异常，就让消费者停下来暴露问题。

## 9. 这一实现明确不提供什么保证

最后再把边界说清楚一次，避免把“本地高性能发布”误读成“可靠消息系统”。

这套实现只保证：

- 当前 JVM 内的内存可见性与 sequence 协调
- 固定容量下不覆盖未越过 gating sequence 的槽位
- 消费者按 sequence 顺序读取
- 多生产者下 cursor 只在连续区间内推进

它**不保证**：

- 进程退出后数据仍然存在
- 消费者重启后能 replay
- 消息被远端机器收到
- 每条消息有 ACK
- 失败后自动重试
- 分布式事务
- 跨进程 exactly-once / at-least-once / at-most-once 语义

所以更准确的定位应该是：

> 一个 JVM-local、固定容量、预分配槽位、基于 sequence 协调的广播式 ring-buffer publisher。

如果你接受这个边界，它非常适合做**进程内高频事件传递**；如果你需要的是可靠消息中间件，请往更高一层的系统看。
