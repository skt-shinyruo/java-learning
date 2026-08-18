# Java `CAS`：实现原理、内存语义与常见应用

本文档聚焦 `CAS`（`Compare-And-Set` / `Compare-And-Swap`）在 Java 里的实现链路：从 `AtomicInteger` / `VarHandle` 这样的 Java API，一路到 `Unsafe`、HotSpot intrinsic、CPU 原子指令，以及它在 JMM 下携带的可见性与顺序语义。

如果你想先看一篇把 JMM 主线、`volatile` / `synchronized` / `final` 对比、经典面试题、底层内存屏障以及 `CAS/AQS/ConcurrentHashMap` 串起来的总览，可以先读 [jmm-notes.md](./jmm-notes.md)。

如果你想继续看 `AQS` / `ReentrantLock` 里 `volatile + CAS + park/unpark` 的配合方式，可以再结合阅读 [lock-support.md](./lock-support.md)。

对应代码位置：
- 并行递增计数器示例：`concurrency/src/main/java/yier/bubu/concurrency/ParallelCounter.java`
- 典型 CAS 自旋抢占名额：`concurrency/src/main/java/yier/bubu/concurrency/ratelimit/ConcurrencyLimiter.java`
- CAS 与 `synchronized` 混合使用：`concurrency/src/main/java/yier/bubu/concurrency/ratelimit/AdaptiveConcurrencyLimiter.java`

## 1. CAS 是什么

CAS 可以先记成一句话：

- **如果共享变量当前值仍然等于“我之前看到的旧值”，就把它原子地更新成新值；否则失败。**

最常见的写法例如：

```java
AtomicInteger count = new AtomicInteger(0);
boolean ok = count.compareAndSet(0, 1);
```

这段代码的意思是：

- 只有当 `count` 当前确实还是 `0` 时，才把它改成 `1`
- 这个“比较 + 写回”是一个不可分割的原子动作
- 如果中间别的线程先把值改掉了，这次更新就失败，返回 `false`

所以 CAS 的关键不在于 `if` 判断，而在于：

- **比较旧值和条件写回新值，必须作为一个整体完成，不能被其他线程插入打断。**

---

## 2. 为什么需要 CAS：`count++` 为什么不安全

很多共享变量更新，看起来只有一行，实际上不是原子的。

例如：

```java
count++;
```

它通常会被拆成：

1. 读旧值
2. 基于旧值计算新值
3. 写回新值

如果两个线程同时执行，很容易出现“丢失更新”：

```text
初始 count = 0

线程 A 读到 0
线程 B 读到 0
线程 A 算出 1 并写回
线程 B 也算出 1 并写回

最终结果 = 1，而不是 2
```

`volatile` 能解决“看不见最新值”的问题，但不能把上面整个“读 -> 改 -> 写”过程变成一个原子事务。

CAS 的思路则是：

1. 先读一个旧值
2. 基于旧值计算新值
3. 提交时检查“当前值还是不是我刚才看到的旧值”
4. 只有还相等才更新，否则说明数据已经过期，需要重试

因此，CAS 是对“基于旧值更新共享变量”这个场景的直接支持。

---

## 3. Java 层是怎么暴露 CAS 的

Java 里你最常见到 CAS 的入口有两类：

- `AtomicInteger`、`AtomicLong`、`AtomicReference` 这些原子类
- `VarHandle` 这套更通用的低层访问机制

例如 `AtomicInteger` 的 `compareAndSet`：

```java
AtomicInteger value = new AtomicInteger(0);
value.compareAndSet(0, 1);
```

从实现思路上，可以把它简化理解成：

```java
class AtomicIntLike {
    private volatile int value;
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long VALUE_OFFSET = ...;

    boolean compareAndSet(int expected, int update) {
        return U.compareAndSetInt(this, VALUE_OFFSET, expected, update);
    }
}
```

这里最关键的是两点：

- `value` 是一个 `volatile` 字段
- CAS 最终不是按“字段名”操作，而是按“对象引用 + 字段偏移量”去定位目标内存槽位

也就是说，Java 层会把“我要改哪个变量”翻译成：

- 哪个对象
- 对象里的哪个偏移地址
- 期望值是什么
- 新值是什么

然后把这些信息交给 JVM 更底层的原子操作能力。

### 3.1 引用类型的 CAS 比较的是 `==`，不是 `equals()`

这是一个非常容易漏掉的细节。

对 `AtomicReference<T>` 来说，CAS 比较的是：

- **当前引用和 `expected` 是否是同一个对象引用**

而不是：

- 两个对象的 `equals()` 是否返回 `true`

例如：

```java
AtomicReference<String> ref = new AtomicReference<>(new String("x"));

String expected = new String("x");
boolean ok = ref.compareAndSet(expected, "y");
```

这里即使两个字符串内容一样，`ok` 也会是 `false`，因为：

- `expected` 和 `ref` 当前保存的对象不是同一个引用

所以引用类型 CAS 要特别注意：

- 你比较的是“对象身份”
- 不是“对象值是否相等”

这也是为什么 `AtomicReference` 特别适合：

- 原子替换某个共享对象引用
- 构建无锁链表/栈/队列中的头尾指针竞争

但如果你脑子里想的是“按业务值比较”，那就容易写错。

### 3.2 `VarHandle` 做了什么

`VarHandle` 是 Java 9 之后更统一的低层并发访问抽象。

它不只提供 `compareAndSet`，还把不同的内存语义区分得更细，例如：

- `getVolatile`
- `setRelease`
- `compareAndSet`
- `compareAndExchange`
- `weakCompareAndSetAcquire`
- `weakCompareAndSetRelease`

这说明 Java 不只在暴露“原子更新”这个能力，还在暴露：

- 读取按什么语义读
- 写入按什么语义写
- 成功和失败时携带什么内存顺序约束

换句话说，现代 Java 里的 CAS 不是“裸硬件指令直接露出来”，而是“带 JMM 语义的原子更新 API”。

### 3.3 除了 `AtomicXxx`，还能怎么对普通字段做 CAS

不是所有场景都要额外包一层 `AtomicInteger` / `AtomicReference`。

Java 里还有两类常见做法：

- `VarHandle`
- `AtomicIntegerFieldUpdater` / `AtomicReferenceFieldUpdater`

例如，一个对象里已经有现成字段时，你可以直接对这个字段做原子更新：

```java
class Node {
    volatile int state;
}
```

然后通过 `VarHandle` 或 `AtomicIntegerFieldUpdater` 去对 `state` 做 CAS，而不是再额外创建一个 `AtomicInteger state`。

这类方式的价值在于：

- 少一层对象包装
- 更适合节点很多的数据结构
- 能直接操作已有对象布局中的 `volatile` 字段

不过 `field updater` 也有一个容易被忽略的限制：

- 它只能保证**通过这个 updater 发起的原子访问之间**的语义
- 如果还有别的代码绕过 updater 直接乱改同一个字段，语义就没那么干净了

所以从今天的工程实践看：

- 新代码更常见的是 `VarHandle`
- 老代码或特定 JUC 结构里仍然会看到 `Atomic*FieldUpdater`

### 3.4 RocketMQ 案例：为什么位置字段使用 `AtomicIntegerFieldUpdater`

RocketMQ 的 `DefaultMappedFile` 维护三个位置状态：

- `wrotePosition`：已经写入的位置
- `committedPosition`：已经提交到文件通道的位置
- `flushedPosition`：已经刷盘的位置

它们都是直接嵌入 `DefaultMappedFile` 对象的 `volatile int`，并分别由三个类级共享的
`AtomicIntegerFieldUpdater` 操作。这里是**一个字段对应一个 updater**，不是一个 updater 同时操作三个字段。

这种设计保留了原子读写、CAS 和原子加法能力，同时避免为每个位置创建独立的
`AtomicInteger`：

```java
class MappedFileState {
    private volatile int wrotePosition;
    private volatile int committedPosition;
    private volatile int flushedPosition;

    private static final AtomicIntegerFieldUpdater<MappedFileState> WROTE_POSITION_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(MappedFileState.class, "wrotePosition");
    // committedPosition 和 flushedPosition 各自还有一个静态 updater
}
```

需要准确区分“字段”和“对象”：

- Java 字段本身没有对象头
- `AtomicInteger` 是独立的堆对象，才有对象头和对齐开销
- 宿主对象还需要保存指向 `AtomicInteger` 的引用
- `static` updater 自身也是对象，但整个类的所有实例共享，开销不会随 `DefaultMappedFile` 实例数线性增长

在典型的 64 位 HotSpot、开启压缩类指针和压缩普通对象指针、按 8 字节对齐的前提下，
一个只包含 `int value` 的 `AtomicInteger` 通常占 16 字节：

```text
对象头 12 字节 + int 4 字节 = 16 字节
```

因此，与这三个位置相关的增量空间可以粗略估算为：

| 方案 | 宿主对象中的字段 | 额外对象 | 合计 |
| --- | ---: | ---: | ---: |
| 三个 `AtomicInteger` | 3 个压缩引用，约 12 字节 | 3 x 16 = 48 字节 | 约 60 字节 |
| 三个 `volatile int` + updater | 3 x 4 = 12 字节 | 每个实例 0 字节 | 约 12 字节 |

所以在上述假设下，每个 `DefaultMappedFile` 大约少三个对象、节省 48 字节。实际结果仍会受
JDK 版本、压缩指针配置、字段重排和对象对齐影响，应以 JOL 等工具的实测结果为准。对象布局的
计算方法见 [Java Object Layout](../../jvm/content/java-object-layout.md)。

这项优化的收益主要随 `DefaultMappedFile` 的**实例数量**增长，而不是随每秒处理的消息数直接增长：

- 创建一个 `DefaultMappedFile` 时少分配三个对象，并不是每处理一条消息就少分配三个对象
- 更少的对象和引用关系可以降低堆占用，并减少 GC 遍历存活对象图的工作量
- 状态直接嵌入宿主对象还省去一次引用间接访问，可能改善访问局部性

但不能只根据“每秒百万级消息”就断言该优化对吞吐量至关重要。它对延迟、吞吐和 GC 的实际收益
取决于 `DefaultMappedFile` 数量、对象生命周期、访问模式和 JVM 配置，需要通过内存剖析与基准测试验证。

同样不能把这种布局直接解释成“减少伪共享”。三个相邻的 `volatile int` 可能落在同一个 cache line；
如果不同线程分别频繁写它们，反而可能产生 cache line 竞争。减少对象间接访问、改善空间局部性和
避免伪共享是三个不同的问题，不能混为一谈。

最后，`AtomicIntegerFieldUpdater` 虽然在 JDK API 中被称为 reflection-based utility，但反射主要用于
创建 updater 时定位并校验字段；热点更新不是每次通过 `Field.get()` / `Field.set()` 完成。可以结合
[RocketMQ `DefaultMappedFile` 源码](https://github.com/apache/rocketmq/blob/73e8fdbdb8b04282305ff579cf0901835bb983b5/store/src/main/java/org/apache/rocketmq/store/logfile/DefaultMappedFile.java)
和 [JDK 17 `AtomicIntegerFieldUpdater` API](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/atomic/AtomicIntegerFieldUpdater.html)
查看完整约束与操作语义。

---

## 4. `AtomicInteger.compareAndSet()` 往下是怎么实现的

如果继续往下追一层，以 OpenJDK 17 为例，大致会看到这条链：

1. `AtomicInteger.compareAndSet(expected, update)`
2. 调到底层的 `Unsafe.compareAndSetInt(this, offset, expected, update)`
3. `Unsafe.compareAndSetInt(...)` 是一个 `native` 方法
4. 它还带有 `@IntrinsicCandidate`
5. HotSpot 会把它识别成一个可内联、可特殊编译的原子操作
6. 最终在目标 CPU 上生成 compare-exchange 原子指令或等价序列

这里 `@IntrinsicCandidate` 很重要。

它的意思不是“这一定会走某个固定实现”，而是：

- **HotSpot 知道这个调用点不是普通业务方法**
- **JIT 可以把它识别成更底层的特殊原语来编译**

因此，在热点路径上，CAS 往往不会退化成“普通 Java 调用 -> 慢速 JNI -> 再执行 C 函数”，而更像是：

- Java 代码写起来像方法调用
- JVM 运行时把它翻译成专门的原子更新节点
- JIT 进一步生成目标平台上的机器码

所以 CAS 的原子性来源不是：

- 给 JVM 上一个全局大锁
- 把所有线程停下来
- 用 `synchronized` 偷偷包一层

而是来源于：

- **JVM 能把这类操作直接映射到底层硬件提供的原子更新能力。**

### 4.1 源码长这样，不等于最终一定按源码逐步执行

阅读 OpenJDK 源码时，要特别注意一个边界：

- **源码展示的是语义层和实现层之间的“逻辑接口”**
- **最终跑在 CPU 上的机器码，可能比源码里看到的路径更短、更专门化**

例如：

- `AtomicInteger` 在 OpenJDK 17 里仍然通过 `Unsafe` 暴露 CAS 和一些原子更新接口
- 但这些 `Unsafe` 方法本身带有 `@IntrinsicCandidate`
- HotSpot 可能直接把它们识别成专门的 IR 节点或机器指令序列

所以有两件事要分开看：

1. **源码怎么表达语义**
2. **JIT 最终怎么编译热点路径**

这也是为什么你会看到两种表面上都“对”的说法：

- “`AtomicInteger.incrementAndGet()` 可以理解成 CAS 自旋”
- “在某些平台/某些热点路径上，它未必真的按手写 CAS 循环那样执行”

这两句话并不矛盾。

更准确的理解是：

- 从语义和源码层面，它是“基于原子更新原语的读改写操作”
- 从运行时实现层面，HotSpot 可能进一步把它做成更直接的机器级原子操作

---

## 5. CPU 层到底怎么保证“比较 + 写回”是原子的

到了 CPU 这一层，重点就变成：怎样让某个内存地址上的“比较旧值 + 条件写入新值”看起来像一个不可分割的整体。

不同架构的实现方式不一样，但目标是一致的。

### 5.1 在 x86 / x64 上

常见思路是用类似 `lock cmpxchg` 这样的原子 compare-exchange 指令。

可以粗略理解为：

- 处理器尝试对目标内存位置执行比较
- 如果当前值等于期望值，就在同一个原子步骤里写入新值
- 否则不写，并把失败结果返回给上层

这里的关键不是“锁住整台机器”，而是：

- 借助 CPU 的原子指令和缓存一致性协议
- 让这块内存所在的 cache line 在提交期间具备排他更新效果

所以更准确的理解是：

- **CAS 原子性更多来自 cache coherence + 原子指令协议，而不是你想象中的“把总线永远锁死”。**

### 5.2 在 ARM / AArch64 上

ARM 一类更弱内存模型的架构，常见做法可能是：

- 使用专门的 CAS 指令
- 或者使用 `LDXR/STXR`、`LDAXR/STLXR` 这样的 exclusive load/store 指令对

思路通常是：

1. 先做一次“独占式读取”
2. 计算新值
3. 尝试以“只有没人抢占过这个地址才写回”的方式提交
4. 如果失败，再重试

所以不同架构虽然长得不一样，但本质一致：

- **都在努力把某个地址上的 compare-exchange 变成一个可线性化的原子动作。**

### 5.3 为什么 Java 代码能跨平台保持统一语义

因为 Java 规范并不要求你直接面向具体机器指令编程。

更准确地说：

- JMM 规定“Java 程序必须看到什么语义”
- HotSpot 负责把这些语义翻译成具体平台上的指令、屏障和原子更新序列

这和 `volatile` 很像：

- Java 层看到的是统一的并发语义
- JVM 层负责为 x86、ARM、AArch64 等不同硬件补齐实现细节

### 5.4 为什么热点单点 CAS 会拖慢系统：cache line ping-pong 与 false sharing

CAS 的代价不只是“失败了要重试”。

如果很多核心反复更新同一个热点变量，例如一个全局计数器：

- 每次成功 CAS 都会让对应 cache line 的所有权在多个核心之间来回转移
- 这会带来很强的 cache coherence 开销
- 常见表现就是所谓的 **cache line ping-pong**

也就是说：

- 线程不一定都在“业务上有冲突”
- 但只要它们都抢着改同一个内存位置，就会在硬件层面形成严重争用

另一个相关问题是 **false sharing（伪共享）**：

- 两个线程更新的是不同字段
- 但这些字段碰巧落在同一个 cache line 上
- 于是它们仍然会互相干扰

这也是为什么高并发统计经常不直接用单点 `AtomicLong`，而改用 `LongAdder`：

- 把更新分散到多个 cell 上
- 降低对单一 cache line 的争抢
- 用空间换吞吐

不过它也有代价：

- `sum()` 不是严格原子快照
- 更适合统计、计数、频率表
- 不适合精细同步控制

---

## 6. 为什么很多原子操作最终会写成 CAS 自旋

CAS 直接解决的是：

- “如果当前值还是旧值，就原子更新”

但很多你常用的操作并不是单纯“把值改成一个常量”，而是：

- 读旧值
- 基于旧值做计算
- 再尝试提交

例如：

- `incrementAndGet()`
- `getAndAdd(delta)`
- 状态位切换
- 一些引用替换和链表头插入

这类操作最典型的实现方式就是 CAS 自旋：

```java
for (;;) {
    int current = value;
    int next = current + 1;
    if (compareAndSet(current, next)) {
        return next;
    }
}
```

它背后的逻辑是：

- 我先乐观地假设冲突不大
- 不加锁直接试
- 如果失败，说明旧值已经过期，重新读新值再试

所以很多原子类虽然你看到的是：

```java
count.incrementAndGet();
```

但它背后经常是：

- 直接用硬件的 `get-and-add` 原语
- 或者由 JDK / JVM 用 CAS 循环拼出来

因此，一个很重要的认识是：

- **“基于 CAS 的原子操作”不等于“底层一定只是一条固定的 CPU 指令”。**

有些操作可以直接映射成硬件原语；
有些则是基于 CAS 循环组合出来的。

### 6.1 不要把所有原子 API 都脑补成“手写 CAS 死循环”

学习阶段把原子更新都想成：

```java
for (;;) {
    read
    compute
    cas
}
```

这是有帮助的，但不能把它机械化。

更准确的说：

- 有些 API 在源码层面就是 CAS 循环
- 有些 API 在 JDK 层看起来像循环，但 HotSpot 可能做了 intrinsic 优化
- 有些 API 在目标硬件上本来就有更贴近的原子原语，比如 fetch-and-add

因此，手写 CAS 循环更像是：

- 一种理解原子更新的通用模型

而不总是：

- 运行时逐条照抄执行的唯一形态

---

## 7. CAS 在 JMM 下为什么不只是“原子”

CAS 在 Java 里不只是“原子更新”，通常还带着明确的内存语义。

以 `VarHandle.compareAndSet` 的语义来理解，可以粗略记成：

- 读取当前值时，类似按 `getVolatile` 的语义读
- 成功写入新值时，类似按 `setVolatile` 的语义写

这意味着它通常同时承担：

- 原子更新
- 可见性传播
- 一定的顺序约束

所以它才适合用来构建更高层的并发协议，而不是只适合“某个整数加一”。

### 7.1 `compareAndSet`、`weakCompareAndSet`、`compareAndExchange` 的区别

这几个名字很像，但语义不完全一样。

#### `compareAndSet`

- 成功返回 `true`
- 失败返回 `false`
- 一般是最常用、最直观的强 CAS

#### `weakCompareAndSet`

- 即使当前值和期望值相等，也可能“伪失败”
- 更适合写在循环里不断重试
- 某些平台上更容易映射到底层原子原语

所以 `weakCompareAndSet` 常见于：

- JDK 内部循环实现
- 你已经准备好失败后立刻重试的场景

不过这里要特别注意一个历史包袱：

- 在 `VarHandle` 这一层，`weakCompareAndSet` 这个名字对应的是 volatile 风格语义
- 但在一些老的原子类里，名为 `weakCompareAndSet` 的方法后来被标记为 deprecated，因为它的名字容易让人误以为是 volatile 风格，而实际可能只是 plain 语义

所以在现代 Java 里，更推荐直接看这些更明确的名字：

- `weakCompareAndSetPlain`
- `weakCompareAndSetAcquire`
- `weakCompareAndSetRelease`
- `compareAndExchange*`

#### `compareAndExchange`

- 返回的不是简单的 `true/false`
- 返回的是执行 CAS 时实际观察到的当前值，也就是 witness value
- 成功时返回值等于 `expected`
- 失败时你能直接拿到真实当前值，少一次额外读取

这类 API 更适合：

- 需要知道失败时现场值到底是什么
- 想减少一次单独 `get()` 的场景

### 7.2 Acquire / Release / Volatile 语义

现代 Java 里，很多原子访问还会继续细分为：

- Plain
- Acquire
- Release
- Volatile

可以粗略理解为：

- `Plain`：最弱，不强调线程间同步语义
- `Acquire`：更强调“读之后”的顺序约束
- `Release`：更强调“写之前”的顺序约束
- `Volatile`：通常更强，接近 Java 里传统 `volatile` 读写语义

这说明在 Java 并发里，**原子性** 和 **内存顺序** 是两件既相关又要分开思考的事：

- 原子性回答“会不会被别的线程插进来破坏提交”
- 内存语义回答“这个提交前后的读写能不能乱序、别的线程什么时候能看到”

### 7.3 常见 CAS API 的语义对照

下面这张表说的是 **`VarHandle` access mode 层面的语义**。

为了避免把这些 API 混成一团，可以按“看到当前值时是什么语义”和“成功写入时是什么语义”来记：

| API | 看到 witness value 的语义 | 成功写入时的语义 | 可能伪失败 | 返回值 |
|---|---|---|---|---|
| `compareAndSet` | `getVolatile` | `setVolatile` | 否 | `boolean` |
| `weakCompareAndSet` | `getVolatile` | `setVolatile` | 是 | `boolean` |
| `weakCompareAndSetPlain` | `get` | `set` | 是 | `boolean` |
| `weakCompareAndSetAcquire` | `getAcquire` | `set` | 是 | `boolean` |
| `weakCompareAndSetRelease` | `get` | `setRelease` | 是 | `boolean` |
| `compareAndExchange` | `getVolatile` | `setVolatile` | 否 | witness value |
| `compareAndExchangeAcquire` | `getAcquire` | `set` | 否 | witness value |
| `compareAndExchangeRelease` | `get` | `setRelease` | 否 | witness value |

这里有两个实用记法：

- 对 `boolean` 风格的 CAS，失败时你只知道“没成”
- 对 `compareAndExchange` 风格，失败时你还能直接拿到现场值

还要注意一点：

- **失败路径至少也携带了“读取 witness value”那一侧的语义**

例如：

- `compareAndExchangeAcquire` 失败时，虽然没有写入，但你拿到的 witness value 仍然带有 acquire 风格的读取语义
- `compareAndExchangeRelease` 更强调成功写入那一侧的 release 语义；失败时只有 plain 风格的 witness read

所以 Acquire / Release / Volatile 的区别，并不是“只有成功时才有意义”，而是：

- 成功路径和失败路径的语义强弱本来就不完全对称

### 7.4 CAS 的线性化点：它到底在哪一刻“真的生效”

如果你想进一步理解无锁算法，一个关键概念是 **linearization point（线性化点）**。

对 CAS 来说，可以粗略这样理解：

- **成功 CAS 的线性化点**：就是底层 compare-exchange 真正把值从旧值切到新值的那个原子时刻
- **失败 CAS 的线性化点**：就是观察到“当前值已经不是 expected”的那个原子时刻

这有什么用？

它能帮助你判断：

- 某个无锁操作在什么时候算真正完成
- 多线程交错执行时，哪个瞬间应该被看作“这个操作已经发生”

例如：

```java
if (head.compareAndSet(oldHead, newHead)) {
    return;
}
```

这里真正使链表头生效切换的，不是：

- Java 代码进入 `if` 的时刻

而是：

- 底层 CAS 原子提交成功的那个瞬间

这也是为什么 CAS 特别适合作为：

- 无锁栈头指针
- 无锁队列尾指针
- 同步器状态位

这些结构的“单点提交动作”。

---

## 8. 结合本仓库代码看 CAS

### 8.1 `ParallelCounter`：最典型的原子计数器

`concurrency/src/main/java/yier/bubu/concurrency/ParallelCounter.java` 里直接用了：

```java
AtomicInteger counter = new AtomicInteger(0);
counter.incrementAndGet();
```

这类写法背后的关键点是：

- 多个线程都在改同一个共享计数器
- 如果用普通 `int` 做 `count++`，会丢更新
- `AtomicInteger` 通过 CAS 或更底层原子更新原语，保证递增结果不会被覆盖

所以：

- 它不需要像 `synchronized` 那样把整个临界区锁住
- 但它也不只是“普通字段换成对象包装类”

### 8.2 `ConcurrencyLimiter`：手写 CAS 自旋协议

仓库里的 `concurrency/src/main/java/yier/bubu/concurrency/ratelimit/ConcurrencyLimiter.java` 非常适合拿来解释 CAS：

```java
while (true) {
    int current = inFlight.get();
    if (current >= maxInFlight) {
        return null;
    }
    if (inFlight.compareAndSet(current, current + 1)) {
        return new Permit(this);
    }
}
```

这里可以按时序理解：

1. 先读取当前并发数 `current`
2. 判断是否已经超过上限
3. 如果没超过，就尝试把 `current` 原子地改成 `current + 1`
4. 如果 CAS 失败，说明有别的线程已经先一步修改了 `inFlight`
5. 那么当前线程必须重新读，再重新判断，再重新抢

这段代码是一个非常标准的“乐观并发 + CAS 提交”例子：

- 不先加锁
- 不把所有竞争线程都挂起
- 先抢，占不到再重试

### 8.3 `AdaptiveConcurrencyLimiter`：CAS 不是锁的替代品

`AdaptiveConcurrencyLimiter` 里有两类状态更新：

- `inFlight` 这种单变量竞争，用 `AtomicInteger` + CAS
- `limit` 的复杂调节逻辑，用 `synchronized`

这说明一个很重要的工程判断：

- **CAS 擅长单变量的原子抢占或状态翻转**
- **多个状态要一起保持一致时，锁往往更直接、更安全**

也就是说，CAS 不是“比锁更高级的万能替代品”，而是更适合某一类特定问题。

### 8.4 `AQS/ReentrantLock`：`volatile + CAS + 队列 + park/unpark`

继续往上一层，AQS 这类同步器通常会把几种机制拼起来：

- `volatile` 字段保存共享状态
- CAS 负责竞争更新共享状态
- 队列负责排队
- `park/unpark` 负责阻塞与唤醒

这也是为什么如果你单独看 `LockSupport`，会发现它并不直接负责同步正确性。

真正保证“谁该成功、谁该等待、状态什么时候对别人可见”的，依然是：

- `volatile`
- CAS
- 队列协议

如果想继续看这一层，可以直接接着读 [lock-support.md](./lock-support.md)。

---

## 9. CAS 的优点和局限

### 9.1 优点

CAS 最明显的优点有这些：

- 在低竞争场景下开销通常很低
- 避免了很多线程阻塞/唤醒和上下文切换
- 很适合短小、单变量的原子更新
- 是很多无锁/少锁并发结构的基础

因此，在：

- 计数器
- 标志位翻转
- 引用替换
- 队列/栈头节点竞争
- 部分并发容器和同步器状态竞争

这些场景里，CAS 很常见。

### 9.2 ABA 问题

CAS 只检查“当前值是不是等于期望值”，不关心这个值中间有没有变化过。

典型 ABA 问题是：

1. 线程 A 看到值是 `A`
2. 线程 B 把值改成 `B`
3. 线程 B 又把值改回 `A`
4. 线程 A 再做 CAS，发现还是 `A`，于是误以为“期间没人动过”

在只关心“当前值是否相等”的场景里，这没问题；
但在关心“这个位置是否曾经被改动过”的场景里，这就会出错。

常见解决方式包括：

- 加版本号
- 使用 `AtomicStampedReference`
- 自己维护“值 + 版本”的组合状态

#### 9.2.1 一个典型场景：无锁栈为什么会被 ABA 坑到

假设一个无锁栈当前是：

```text
head -> A -> B -> C
```

线程 T1 准备把栈顶 `A` 弹出：

1. 读到 `head = A`
2. 读到 `next = B`
3. 计划做 `CAS(head, A, B)`

但在它提交前，线程 T2 插进来做了这些事：

1. 弹出 `A`，栈变成 `B -> C`
2. 再弹出 `B`，栈变成 `C`
3. 又把 `A` 压回去，栈变成 `A -> C`

这时 T1 醒来一看：

- 当前 `head` 还是 `A`

于是它的 `CAS(head, A, B)` 竟然成功了。

问题在于：

- T1 以为自己把 `A` 后面的节点接成了原来读到的 `B`
- 但此时 `B` 已经不是那个合法的当前后继了

这就是 ABA 真正危险的地方：

- **值表面上回到了 A**
- **但结构语义已经不是原来的那个 A 场景了**

#### 9.2.2 `AtomicStampedReference` 和 `AtomicMarkableReference` 各适合什么

`AtomicStampedReference` 的核心思想是：

- 不只比较引用值
- 还比较一个随更新递增的 stamp（版本号）

于是从：

- `(A, stamp=1)` -> `(B, 2)` -> `(A, 3)`

这种变化里，后一个 `A` 就不再会被误认为“还是最开始那个没变过的 A”。

`AtomicMarkableReference` 则更轻一些，它把引用和一个布尔标记绑在一起。

它更适合：

- 逻辑删除
- 某个节点是否被标记过
- 某个引用是否处于特定布尔状态

但它不等价于完整版本号。

所以粗略选择可以这样记：

- **要解决 ABA 的“发生过几次变化”问题，优先看 `AtomicStampedReference`**
- **要表达“是否被标记/逻辑删除”这类二值状态，`AtomicMarkableReference` 更合适**

### 9.3 高竞争下的自旋成本

CAS 失败通常意味着要重试。

如果竞争很激烈，就会出现很多线程不停地：

- 读
- 比较
- 失败
- 再读
- 再比较

这会导致：

- CPU 空转
- 吞吐下降
- 延迟抖动变大

所以 CAS 并不是竞争越激烈越有优势。高冲突下，锁有时反而更稳。

### 9.4 只天然适合单变量原子更新

CAS 最擅长的是“一个槽位”的更新。

如果你要同时维护多个变量的一致性，例如：

- 余额和流水同时变化
- 链表多个指针要一起更新
- 多个状态位之间要满足组合约束

那么单个 CAS 往往不够。

这时你通常需要：

- 更复杂的多步协议
- 额外版本控制
- 或者直接回到 `synchronized` / `Lock`

### 9.5 代码更难推理

CAS 写起来经常不长，但正确性并不天然简单。

你需要额外考虑：

- 失败重试
- ABA
- 饥饿
- 公平性
- 是否需要回退策略
- 复杂状态下是否仍然线性化

因此，业务代码里如果你只是想稳妥维护共享状态，不要为了“听起来高级”而硬上 CAS。

### 9.6 用了 CAS，不等于就成了真正的“无锁算法”

并发里经常会看到几个容易混淆的词：

- obstruction-free
- lock-free
- wait-free

它们不是同义词。

#### obstruction-free

可以粗略理解成：

- 如果没有别的线程继续干扰，我最终能完成

这是最弱的一类进度保证。

#### lock-free

可以粗略理解成：

- 在系统整体上，始终会有某个线程不断取得进展

它不保证每个线程都公平；
某个线程仍然可能一直失败、一直重试、一直饿死。

#### wait-free

最强的一类，可以粗略理解成：

- 每个线程都能在有限步内完成自己的操作

这在工程上实现难度最高，也最少见。

所以要特别注意：

- “使用了 CAS” 不等于 “wait-free”
- “没有显式加锁” 也不自动等于 “lock-free”

很多手写 CAS 循环最多只能做到：

- 某种程度上的 lock-free，甚至只是 obstruction-free

而不是人人都能稳定前进。

---

## 10. 手写 CAS 自旋时的工程实践

真正自己写 CAS 协议时，除了“会不会成功”，还要考虑很多工程细节。

### 10.1 循环体里的计算尽量无副作用

CAS 失败意味着代码可能重跑多次。

所以像下面这种更新函数，必须尽量满足：

- 可重复执行
- 无副作用
- 不依赖一次性外部状态

否则一旦重试，就可能把副作用放大多次。

这也是为什么 JDK 里的原子更新 API 会强调：

- `updateFunction` / `accumulateFunction` 最好是 side-effect-free

### 10.2 每一轮都要重新读、重新判断、重新计算

CAS 自旋的关键不是“失败了再试一次”这么简单，而是：

- 失败后必须基于**最新值**重新判断条件

例如：

```java
while (true) {
    int current = inFlight.get();
    if (current >= maxInFlight) {
        return null;
    }
    if (inFlight.compareAndSet(current, current + 1)) {
        return success;
    }
}
```

这里 `if (current >= maxInFlight)` 也必须放在循环里，因为：

- 失败后，当前状态可能已经变了

### 10.3 非常短的忙等可以考虑 `Thread.onSpinWait()`

如果你明确在写一个短时间的自旋等待，Java 提供了：

```java
Thread.onSpinWait();
```

它的意义不是“让代码变正确”，而是：

- 告诉 JVM/处理器：我现在处于忙等循环
- 运行时可以在某些平台上发出更适合 spin-wait 的处理器提示

要点是：

- 没有它，代码仍然应该是正确的
- 有了它，某些架构上可能更省资源或更平滑

### 10.4 高竞争下通常需要退避，而不是无脑死转

如果你发现 CAS 失败率很高，常见手段包括：

- 短暂自旋后再试
- 指数退避
- `yield` / `parkNanos`
- 干脆切换到阻塞式同步方案

因为：

- 一直原地死转，往往只会把 cache line 争用放大

### 10.5 多字段一致性、复杂条件、长逻辑时应优先考虑锁

如果你的更新逻辑开始变成：

- 需要一起改多个字段
- 失败回滚很复杂
- 条件判断很长
- 还要处理中断、超时、取消

那通常已经越过了“CAS 最舒服的适用区间”。

这时优先考虑：

- `synchronized`
- `ReentrantLock`
- 现成同步器

通常会更稳，也更容易推理。

### 10.6 热点统计优先看 `LongAdder`

如果你只是做高并发计数，例如：

- 请求数
- 命中数
- 指标统计

那单点 `AtomicLong` / `AtomicInteger` 的 CAS 很容易变成热点瓶颈。

这时更常见的选择是：

- `LongAdder`

但也要明确它的边界：

- 吞吐更好
- 空间更大
- `sum()` 不是严格原子快照
- 适合统计，不适合精细同步控制

---

## 11. 开发里怎么选 `volatile`、CAS、锁

可以按这个思路判断：

- **只是传播一个状态、开关、版本号**：优先考虑 `volatile`
- **单变量原子递增、状态翻转、引用替换**：优先考虑原子类和 CAS
- **多个变量要一起保持一致**：优先考虑 `synchronized` 或 `Lock`
- **高并发热点计数**：优先考虑 `LongAdder`
- **更高层并发协调**：优先考虑 `ConcurrentHashMap`、阻塞队列、线程池、同步器等现成工具

这里最常见的误区有两个：

- 误以为 `volatile` 可以替代原子更新
- 误以为 CAS 可以替代所有锁

更准确的说法是：

- `volatile` 解决“看得见”
- CAS 解决“单变量原子提交”
- 锁解决“更复杂共享状态的一致性与互斥”

---

## 12. 一句话总结

可以把 Java 里的 CAS 记成这句话：

- **CAS 是一种由 Java API、HotSpot intrinsic、CPU 原子指令和 JMM 内存语义共同实现的单变量原子更新机制；它擅长低竞争下的轻量更新，但不能替代所有锁。**

如果再压缩一点：

- **CAS = 比较旧值 + 条件原子写回 + 失败重试 + 内存语义。**
