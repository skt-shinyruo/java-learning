# Kafka `VARINT`、`VARLONG` 与 `base + delta`：为什么要这样编码

Kafka 的很多协议字段和 record 字段，并不一律使用固定长度的 `INT32` / `INT64`，而是混合使用：

- 固定长度整数：便于快速定位、批次 framing 和实现简单性。
- 变长整数：在值通常很小时显著节省字节数。

这个主题里最容易混淆的点有三类：

- `VARINT` 不是“另一种 `INT32`”，而是“先 ZigZag，再 Varint”的编码方式。
- Kafka 不是“所有整数都用 varint”，而是只在高重复、典型值小的字段上用它。
- Kafka 的空间优化不只是 `varint`，还包括把绝对值拆成 `base + delta`，先把大数变成小相对值，再做变长编码。

更准确地说：

- `VARINT` / `VARLONG` 解决的是“小整数如何更省字节地表示”。
- `baseOffset + offsetDelta`、`baseTimestamp + timestampDelta` 解决的是“不要在每条 record 里重复存完整的大数”。

## 1. Kafka 里有哪些整数编码

Kafka 协议里常见的整数相关类型可以粗略分成两类：

- 定长：`INT8`、`INT16`、`INT32`、`INT64`
- 变长：`VARINT`、`VARLONG`、`UNSIGNED_VARINT`

其中：

- `VARINT` 表示有符号 32 位整数，采用 variable-length zig-zag 编码。
- `VARLONG` 表示有符号 64 位整数，规则与 `VARINT` 相同，只是范围更大。
- `UNSIGNED_VARINT` 表示无符号变长整数，常用于 `COMPACT_STRING`、`COMPACT_BYTES`、`COMPACT_ARRAY` 这类紧凑协议类型的长度前缀。

如果一个字段的值通常不大，变长编码常常只需要 `1` 到 `2` 个字节，而不是固定占用 `4` 或 `8` 个字节。

## 2. `VARINT` 是什么

Kafka 的 `VARINT` 不是一步完成的，而是两步：

1. 先把有符号 `int32` 做 `ZigZag` 映射，变成无符号整数
2. 再把这个无符号整数按 `Varint` 规则做变长编码

### 2.1 为什么需要 ZigZag

普通 `Varint` 更适合无符号小整数。

如果直接把负数按二补码做变长编码，负数高位会有大量前导 `1`，编码长度会很差。结果就会变成：

- `1` 很短
- `-1` 却可能很长

这不符合 Kafka 的目标，因为很多字段虽然可能有符号，但绝对值往往很小。

`ZigZag` 的作用，就是把“绝对值小的有符号数”重新映射成“值小的无符号数”：

```text
0  -> 0
-1 -> 1
1  -> 2
-2 -> 3
2  -> 4
-3 -> 5
```

也就是：

- 非负数映射到偶数
- 负数映射到奇数

### 2.2 ZigZag 的公式

对 `int32`：

```text
encode: u = (n << 1) ^ (n >> 31)
decode: n = (u >>> 1) ^ -(u & 1)
```

更直观的等价写法是：

```text
n >= 0  => u = 2n
n < 0   => u = -2n - 1
```

例如：

```text
0    -> 0
1    -> 2
-1   -> 1
2    -> 4
-2   -> 3
300  -> 600
-300 -> 599
```

### 2.3 Varint 的字节规则

ZigZag 之后得到的是无符号整数 `u`。接下来按 Varint 编码：

- 每个字节只拿低 `7` 位存数据
- 最高 `1` 位表示后面是否还有字节
- `1` 表示后面还有
- `0` 表示这是最后一个字节
- 按低位 `7 bit` 组先写出的顺序编码

伪代码可以写成：

```java
while ((u & ~0x7F) != 0) {
    writeByte((u & 0x7F) | 0x80);
    u >>>= 7;
}
writeByte(u);
```

### 2.4 例子

```text
0   -> ZigZag 0   -> 00
-1  -> ZigZag 1   -> 01
1   -> ZigZag 2   -> 02
-2  -> ZigZag 3   -> 03
63  -> ZigZag 126 -> 7E
-64 -> ZigZag 127 -> 7F
64  -> ZigZag 128 -> 80 01
```

可以看到，`[-64, 63]` 这个范围内的 `VARINT` 都只需要 `1` 个字节。

`-300` 的过程更能说明它是如何工作的：

```text
-300 -> ZigZag 599
599  -> D7 04
```

解码时先把 `D7 04` 还原成无符号整数 `599`，再做 ZigZag 反解，就能得到 `-300`。

## 3. Kafka 为什么需要 `varints`

Kafka 需要 `varints`，核心原因不是“支持更大范围”，而是：

```text
Kafka 的很多整数值通常都很小，用固定长度编码太浪费。
```

如果所有字段都用固定长度：

- `INT32` 永远 `4` 字节
- `INT64` 永远 `8` 字节

但 Kafka 里大量字段并不大，例如：

- `length`
- `keyLength`
- `valueLength`
- `headersCount`
- `offsetDelta`
- `timestampDelta`

这些值在真实场景里往往只有几十、几百，甚至更小。此时用 `VARINT` / `VARLONG`，很多字段只要 `1` 到 `2` 字节，而不是 `4` 到 `8` 字节。

这对 Kafka 特别重要，因为它是高吞吐系统：

- 消息会被海量写入和复制
- 同一类字段会在每条 record 上重复出现
- 每条消息省几个字节，累计到批量传输和落盘上就是显著差异

所以 `varints` 的收益主要体现在：

- 降低网络传输开销
- 降低磁盘占用
- 让批量压缩前的原始字节流更紧凑

## 4. 为什么 `offset` / `timestamp` 不直接存绝对值

仅仅把整数换成 `varint` 还不够，因为绝对 `offset` 和绝对 `timestamp` 本身通常就是大数：

- 分区 offset 可能已经非常大
- 时间戳通常是 13 位毫秒值

即使对这些绝对值做变长编码，也未必能省很多。

Kafka 真正关键的一步，是把它们改成：

- `baseOffset + offsetDelta`
- `baseTimestamp + timestampDelta`

也就是：

- 在 `RecordBatch` 外层只存一次基准值
- 在每条 `Record` 里只存相对变化量

### 4.1 `offset` 的例子

假设一个 batch 里有 3 条消息：

```text
9000000
9000001
9000002
```

如果每条都存完整 `int64`，就会重复写 3 个大数。  
Kafka 的做法是：

```text
baseOffset = 9000000
offsetDelta = 0, 1, 2
```

读取时：

```text
recordOffset = baseOffset + offsetDelta
```

### 4.2 `timestamp` 的例子

假设 3 条消息的时间戳是：

```text
1719820000000
1719820000010
1719820000035
```

Kafka 不在每条 record 里重复写完整时间戳，而是写成：

```text
baseTimestamp = 1719820000000
timestampDelta = 0, 10, 35
```

读取时：

```text
recordTimestamp = baseTimestamp + timestampDelta
```

### 4.3 为什么这非常适合 Kafka

Kafka 的 producer 写入天然就是 batch 化的。在同一个 batch 里：

- `offset` 天然连续
- `timestamp` 通常彼此很接近

所以 `delta` 往往远小于绝对值。  
这时再叠加 `varint` / `varlong`，收益就会非常明显。

也就是说 Kafka 做了两层优化：

1. 用 `base + delta` 把大绝对值变成小相对值
2. 用 `varint` / `varlong` 把小相对值进一步压缩

## 5. 哪些字段用了变长编码，哪些没有

Kafka 的编码策略不是“全面变长”，而是有明显分层。

### 5.1 `Record` 内部大量使用变长编码

按 Kafka 4.3 官方 message format，`Record` 内部字段如下：

```text
length: varint
attributes: int8
timestampDelta: varlong
offsetDelta: varint
keyLength: varint
valueLength: varint
headersCount: varint
```

`Record Header` 里也使用了变长长度字段：

```text
headerKeyLength: varint
headerValueLength: varint
```

这类字段的共同点是：

- 每条 record 都会重复出现
- 典型值通常很小
- 顺序解析本来就是合理路径

所以最适合用变长编码。

### 5.2 `RecordBatch` 外层仍然大量使用固定长度

`RecordBatch` 外层很多字段仍然是固定长度：

```text
baseOffset: int64
batchLength: int32
partitionLeaderEpoch: int32
crc: uint32
attributes: int16
lastOffsetDelta: int32
baseTimestamp: int64
maxTimestamp: int64
producerId: int64
producerEpoch: int16
baseSequence: int32
recordsCount: int32
```

这些字段虽然也都是整数，但没有改成变长编码，原因在于它们承担的是 batch 级元数据职责：

- `batchLength` 用来界定整个 batch 边界
- `crc` 用来校验
- `baseOffset` / `baseTimestamp` 是整批 record 的锚点
- 这些字段每个 batch 只出现一次，而不是每条 record 都重复一次

因此，固定长度更利于快速定位、顺序解析和实现简洁性，而收益损失很小。

### 5.3 协议层的紧凑类型使用 `UNSIGNED_VARINT`

Kafka 协议层的 `COMPACT_*` 类型也体现了同样思路，例如：

- `COMPACT_STRING`
- `COMPACT_BYTES`
- `COMPACT_ARRAY`
- `COMPACT_NULLABLE_STRING`
- `COMPACT_NULLABLE_BYTES`

这些类型的长度前缀使用 `UNSIGNED_VARINT`，而不是传统的 `INT16` / `INT32`。

比如 `COMPACT_STRING` 的长度不是直接写 `N`，而是写 `N + 1`：

- `0` 用来表示 `null`（在 nullable 版本里）
- 非空时再用 `N + 1` 表示真实长度

这进一步说明 Kafka 的思路是一致的：

```text
长度字段如果大多是小值，就尽量用变长编码减少协议开销。
```

## 6. 为什么 `offsetDelta` 用 `VARINT`，而 `timestampDelta` 用 `VARLONG`

这两个字段都存的是 delta，但它们的约束不同。

### 6.1 `offsetDelta` 适合 `VARINT`

`offsetDelta` 表示的是“当前 record 相对 batch 起点的位移”。  
它本质上和“这条 record 在 batch 中排第几条”高度相关，因此范围天然受 batch 内记录数约束。

Kafka 在 batch 外层本来就有：

```text
recordsCount: int32
lastOffsetDelta: int32
```

因此 `offsetDelta` 使用 `VARINT` 是自然选择：范围足够，编码也紧凑。

### 6.2 `timestampDelta` 需要 `VARLONG`

`timestampDelta` 表示的是“当前 record 相对 `baseTimestamp` 的时间差”。  
时间戳在 Kafka 里本来就是 `int64` 语义，delta 也不应被强行缩窄成 `int32`。

虽然很多批次里的时间差很小，但从类型设计上：

- 时间戳本身是 64 位
- 相对时间差也应保留 64 位范围

因此 Kafka 选择了 `VARLONG`：平时小值依然很省字节，但不会因为范围设计过窄而限制语义。

## 7. 小结

Kafka 在整数编码上并不是单一策略，而是三层组合：

1. batch 外层保留必要的固定长度锚点字段
2. record 内层大量使用 `VARINT` / `VARLONG` 降低重复字段开销
3. 对 `offset` / `timestamp` 先做 `base + delta`，再做变长编码

所以更准确的理解应该是：

```text
Kafka 不是简单地“用 varint 省空间”，
而是基于 batch 写入模型，把大绝对值提到外层只存一次，
再把内层高重复的小相对值做变长编码。
```

这套设计同时兼顾了：

- 顺序写入吞吐
- 传输和落盘空间效率
- batch 级快速解析
- record 级细粒度压缩

## 参考

- [Kafka Protocol Guide](https://kafka.apache.org/protocol/)
- [Kafka Protocol Types](https://kafka.apache.org/24/generated/protocol_types.html)
- [Kafka 4.3 Message Format](https://kafka.apache.org/43/implementation/message-format/)
- [Protocol Buffers Encoding](https://protobuf.dev/programming-guides/encoding/)
