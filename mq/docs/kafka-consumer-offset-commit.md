# Kafka Consumer offset commit：手动提交与 partition 内并发处理

Kafka consumer 的 offset commit 容易出问题，不是因为提交 API 本身复杂，而是因为它要和业务处理结果保持一致。

更准确地说，offset commit 记录的是：

```text
这个 consumer group 对某个 topic-partition 的下一条消费位置
```

如果提交了 offset `12`，含义不是“offset 12 已经处理完成”，而是：

```text
offset < 12 的消息已经处理完成
下一次恢复时从 offset 12 开始消费
```

## 1. 为什么 offset commit 是难点

常见的两种顺序都有代价：

- 先处理业务，再提交 offset：业务成功但 commit 失败，重启后会重复消费。
- 先提交 offset，再处理业务：commit 成功但业务失败，消息可能丢失。

所以 Kafka consumer 默认更容易做到的是 `at-least-once`：

```text
可以重复，但尽量不丢
```

如果想让业务层接近 `exactly-once`，通常要依赖业务幂等、去重表、事务性写库、outbox 或 Kafka transaction 等机制。

基本原则是：

```text
业务处理成功之后再提交 offset，同时让业务处理具备幂等性。
```

## 2. `position` 和 `committed offset` 不是一回事

Kafka consumer 里有两个容易混淆的 offset 概念。

### 2.1 `position`

`position` 是当前 consumer 实例下一次要拉取的位置。它在 consumer 本地内存里，由 `poll()` 推进。

例如某次 `poll()` 拉到了：

```text
10, 11, 12, 13
```

当前 consumer 对这个 partition 的本地 `position` 通常已经推进到：

```text
14
```

含义是：

```text
这个 consumer 下一次 fetch 从 14 开始
```

### 2.2 `committed offset`

`committed offset` 是 consumer group 提交到 broker 的消费进度。它主要用于：

- consumer 重启后的恢复位置。
- rebalance 后新 consumer 接管分区时的起始位置。
- 分区重新分配后的消费进度恢复。

所以如果当前 consumer 已经 `poll()` 到 `10-13`，本地 `position` 推进到 `14`，然后只提交：

```text
commit offset = 12
```

那么同一个 consumer 在不崩溃、不 rebalance、不手动 `seek()` 的情况下，下一次 `poll()` 一般仍会从 `14` 往后拉，而不会因为 committed offset 是 `12` 就自动倒回去。

但是如果进程崩溃、consumer 重启或发生 rebalance，新 consumer 会从 committed offset `12` 开始消费。

可以简化成：

```text
position: 当前 consumer 这辆车开到哪里了
committed offset: 写进 broker 的检查点在哪里
```

车已经开到 `14`，不会因为检查点写成 `12` 就自动倒车；但车坏了换一辆，新车会从检查点 `12` 出发。

## 3. 手动提交 offset 的基本结构

关闭自动提交：

```java
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
```

手动提交时，提交的是“下一条要消费的 offset”，所以单条消息处理完成后对应的提交值是：

```java
record.offset() + 1
```

一个最简单的批处理模型是：

```java
ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));

for (ConsumerRecord<String, String> record : records) {
    handle(record);
}

consumer.commitSync();
```

这个模型简单，但它没有发挥本地多线程处理能力。

## 4. 本地多线程消费的线程边界

`KafkaConsumer` 不是线程安全的。通常要遵守这个边界：

```text
consumer 线程：poll、pause、resume、seek、commit
worker 线程：只做业务处理
```

worker 线程处理完成后，不直接操作 `KafkaConsumer`，而是把处理结果回报给 consumer 线程。consumer 线程再根据各 partition 的完成情况决定能提交到哪里。

## 5. partition 内并发处理时不能提交最大 offset

如果同一个 partition 内的消息并发处理，完成顺序可能乱序。

例如：

```text
offset: 10 11 12 13
状态:   成功 成功 未完成 成功
```

此时最多只能提交：

```text
commit offset = 12
```

因为 offset `12` 还没处理完成，不能提交 `14`。一旦提交 `14`，进程崩溃后 Kafka 会认为 `10-13` 都已经处理完成，offset `12` 就可能被跳过。

提交 `12` 的含义是：

```text
10、11 已经安全完成
故障恢复时从 12 开始
```

所以如果发生重启或 rebalance，`12` 和 `13` 都可能被重新消费。`13` 虽然已经处理成功，但因为前面的 `12` 没完成，提交点不能越过 `12`。

这就是 partition 内并发处理的典型代价：

```text
后面的成功消息，可能因为前面的慢消息或失败消息而重复消费。
```

## 6. 正确做法：维护连续完成水位线

partition 内并发处理不是不能做，但 commit 必须按连续完成的 offset 推进。

规则是：

```text
只能提交从上一次提交点开始，连续处理成功的最大 offset + 1。
```

可以为每个 `TopicPartition` 维护一个 offset tracker：

```java
class OffsetTracker {
    private long nextCommitOffset;
    private final TreeSet<Long> completed = new TreeSet<>();

    OffsetTracker(long initialOffset) {
        this.nextCommitOffset = initialOffset;
    }

    synchronized void markDone(long offset) {
        completed.add(offset);

        while (completed.remove(nextCommitOffset)) {
            nextCommitOffset++;
        }
    }

    synchronized long committableOffset() {
        return nextCommitOffset;
    }
}
```

当 worker 处理完某条消息后，只标记这个 offset 完成。`OffsetTracker` 会从 `nextCommitOffset` 开始向前合并连续完成的 offset。

例如：

```text
nextCommitOffset = 10
完成: 10
可提交: 11

完成: 11
可提交: 12

完成: 13
可提交: 12

完成: 12
可提交: 14
```

## 7. 并发处理的简化代码

下面代码只表达核心结构：consumer 线程负责 `poll` 和 `commit`，worker 线程处理业务并回报完成的 offset。

```java
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
ExecutorService workers = Executors.newFixedThreadPool(16);

Map<TopicPartition, OffsetTracker> trackers = new HashMap<>();
BlockingQueue<Done> doneQueue = new LinkedBlockingQueue<>();

while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));

    for (ConsumerRecord<String, String> record : records) {
        TopicPartition tp = new TopicPartition(record.topic(), record.partition());

        trackers.computeIfAbsent(tp, ignored -> new OffsetTracker(record.offset()));

        workers.submit(() -> {
            try {
                handle(record);
                doneQueue.add(new Done(tp, record.offset()));
            } catch (Exception e) {
                // 不能 markDone，否则会跳过失败消息。
                // 这里应该重试，或者写入 DLT 成功后再 markDone。
            }
        });
    }

    Done done;
    while ((done = doneQueue.poll()) != null) {
        OffsetTracker tracker = trackers.get(done.tp());
        if (tracker != null) {
            tracker.markDone(done.offset());
        }
    }

    Map<TopicPartition, OffsetAndMetadata> commits = new HashMap<>();

    for (Map.Entry<TopicPartition, OffsetTracker> entry : trackers.entrySet()) {
        commits.put(
                entry.getKey(),
                new OffsetAndMetadata(entry.getValue().committableOffset())
        );
    }

    if (!commits.isEmpty()) {
        consumer.commitAsync(commits, (offsets, exception) -> {
            if (exception != null) {
                // 记录日志；关闭或 rebalance 前用 commitSync 兜底。
            }
        });
    }
}
```

`Done` 可以很简单：

```java
record Done(TopicPartition tp, long offset) {
}
```

## 8. 生产实现要补上的控制点

partition 内并发处理会提高处理吞吐，但会引入几个必须显式处理的问题。

### 8.1 失败消息策略

如果某个低 offset 一直失败，它会卡住后面所有 offset 的提交。

例如：

```text
10 成功
11 失败或超时
12 成功
13 成功
14 成功
```

此时最多只能提交：

```text
commit offset = 11
```

即使 `12-14` 都已经成功，进程崩溃后它们也可能重复消费。

所以失败消息必须有明确策略：

- 同步重试。
- 延迟重试。
- 写入 DLT。
- 人工修复后再恢复。

只有业务成功，或者失败消息已经被可靠转移到 DLT，才能把该 offset 标记为完成。

### 8.2 限制每个 partition 的 in-flight 数量

如果不限制并发中的消息数量，一个低 offset 卡住后，后面会堆积大量“已经完成但不能提交”的 offset。

常见做法是：

- 每个 partition 维护 in-flight 计数。
- 超过阈值后对该 partition `pause(tp)`。
- 完成数量下降后再 `resume(tp)`。

### 8.3 rebalance 时提交安全 offset

发生 rebalance 时，分区可能被分配给其他 consumer。`onPartitionsRevoked` 里应该尽量提交当前已经连续完成的 offset。

简化结构：

```java
consumer.subscribe(topics, new ConsumerRebalanceListener() {
    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        consumer.commitSync(currentCommitOffsetsFor(partitions));
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
    }
});
```

更严格的实现还需要在 revoked 前停止对应 partition 的新任务派发，等待已派发任务完成或超时，然后提交连续完成水位线。

### 8.4 业务幂等

即使 offset tracker 实现正确，也仍然可能重复消费：

- commit 请求失败。
- 进程在业务成功后、commit 成功前崩溃。
- rebalance 前来不及提交。
- 高 offset 已处理成功，但低 offset 卡住导致提交点无法推进。

所以业务侧仍然要具备幂等或去重能力。

## 9. 小结

如果本地多线程消费只要求跨 partition 并发，可以让每个 partition 保序处理，commit 逻辑会简单很多。

如果明确要 partition 内并发处理，关键不是禁止乱序完成，而是：

```text
处理可以乱序完成，commit 必须按连续完成水位线推进。
```

提交 `12` 后，当前 consumer 不会自动从 `12` 重新 poll，因为本地 `position` 可能已经推进到 `14`。但如果发生重启、rebalance 或新 consumer 接管，就会从 committed offset `12` 开始恢复，因此 `12` 和后面的已处理消息都可能重复消费。

这就是 partition 内并发消费的核心取舍：

```text
吞吐更高，但需要 offset 连续水位线、失败处理、背压、rebalance 处理和业务幂等。
```
