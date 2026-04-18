# volatile：可见性与禁止重排（JMM 视角）

本文档配合 `concurrency` 模块中的示例与测试，说明 `volatile` 在 Java 内存模型（JMM）下的核心语义：**可见性**与**有序性（通过 happens-before / release-acquire 语义形成的重排约束）**。

如果你想先看一篇把 JMM 主线、`volatile` / `synchronized` / `final` 对比、经典面试题、底层内存屏障以及 `CAS/AQS/ConcurrentHashMap` 串起来的总览，可以先读 [jmm-notes.md](./jmm-notes.md)。
如果你想看一组专门说明“volatile 放在不同位置为什么语义完全不同”的 JCStress 例子，可以继续看 [jcstress-ordering-partial.md](./jcstress-ordering-partial.md)。

对应代码位置：
- 单元测试（带结论断言 + 中文说明）：`concurrency/src/test/java/yier/bubu/concurrency/VolatileVisibilityAndReorderingTest.java`
- JCStress 示例（统计不同交错下的结果分布）：`concurrency/src/test/java/yier/bubu/concurrency/jcstress/`
  - `TestOrderingPartial`
  - `TestVisibility`
- 演示类（被测试引用，可在其他地方复用）：`concurrency/src/main/java/yier/bubu/concurrency/jmm/`
  - `VolatileStopFlagDemo`
  - `VolatilePublishDemo`
  - `NonVolatileStopFlagDemo`（反例，概率性）
  - `NonVolatilePublishDemo`（反例，概率性）

## 1. 为什么需要 volatile：问题是什么

在没有任何同步手段（`volatile` / `synchronized` / `Lock` / 原子类等）的情况下：
- 一个线程对共享变量的写入，**不保证**另一个线程能在“合理时间内”看到最新值（可见性问题）。
- 编译器/JIT/CPU 允许在不破坏“单线程语义”的前提下做优化，包括把读写缓存到寄存器、调整指令顺序等。  
  对多线程来说，如果没有 happens-before 约束，这些优化会表现为“看起来像指令重排”或“读到旧值”。

结论先行：
- `volatile` **不保证复合操作的原子性**（例如 `count++` 仍然不是原子的）。
- `volatile` 能提供：
  - **可见性**：写入能被其他线程读到；
  - **有序性**：对 `volatile` 的读/写会形成特定的重排约束，并能建立 happens-before 关系（发布/读取模型里非常关键）。

## 2. 为什么 `volatile int count; count++` 仍然不线程安全

很多人第一次接触 `volatile` 时，会自然地问：既然它能保证“线程之间能看到最新值”，那把计数器写成下面这样是不是就安全了？

```java
volatile int count = 0;
count++;
```

答案是：**不安全**。

根本原因不在于“看不见最新值”，而在于 `count++` 不是一个原子操作。它至少包含三步：

```java
int oldValue = count;     // volatile 读
int newValue = oldValue + 1;
count = newValue;         // volatile 写
```

`volatile` 只保证：
- 这次读，读到的是当前“可见”的值；
- 这次写，写出的值会对其他线程可见；
- `volatile` 读写前后有额外的重排约束。

它**不保证**上面这整个“读 -> 改 -> 写”序列是一个不可分割的整体，因此不能防止“丢失更新”。

### 2.1 两个线程同时执行 `count++` 的时间线

假设主内存中的 `count` 初始值为 `0`，线程 A 和线程 B 同时执行 `count++`，可能发生下面这种交错：

```text
主内存中的 count 初始值 = 0

时间    线程 A                          线程 B                          主内存 count
----    -----------------------------   -----------------------------   ------------
t1      读取 count，得到 0                                            0
t2                                      读取 count，得到 0             0
t3      在本地计算：0 + 1 = 1                                         0
t4                                      在本地计算：0 + 1 = 1          0
t5      把 1 写回 count                                              -> 1
t6                                      把 1 写回 count               -> 1
```

最终结果是 `1`，但实际上发生了两次加一，正确结果应该是 `2`。
这就是典型的 **lost update（丢失更新）**。

也可以把它压缩成更直观的读法：

```text
线程 A                 线程 B                 共享变量 count
------                 ------                 --------------
read count -> 0                                0
                       read count -> 0         0
local +1 -> 1                                  0
                       local +1 -> 1           0
write 1                                         1
                       write 1                 1   <- 把前一次结果覆盖了
```

### 2.2 为什么线程 B 写回时不会“顺便再读一次最新值”

这正是很多人最容易误解的地方。

在线程 B 的这次 `count++` 执行里，它的语义更接近：

```java
int oldValue = count;   // 这里已经读到了 0
int newValue = oldValue + 1;  // 算出 1
count = newValue;       // 直接把 1 写回
```

重点在最后一步：
- B 在写回时，写的是自己前面已经算好的 `newValue`
- 它不会自动变成“先重新读一遍 `count`，如果发现别人改过了，再重新计算再写回”

所以在上面的时间线里：
- A 在 `t5` 把 `count` 写成了 `1`
- B 到了 `t6` 时，不会因为 A 已经写过 `1`，就自动把自己的结果调整成 `2`
- B 只是把它在 `t4` 就算好的 `1` 再写一次，于是把 A 的更新覆盖掉

这也是为什么 `volatile` 无法替代原子更新语义：
它没有把“基于旧值计算，再按条件写回”这件事封装成不可分割的事务。

### 2.3 从 JMM 角度怎么理解

`volatile` 的读写之间，可以建立 happens-before 关系；对同一个 `volatile` 变量：
- 写线程的 `volatile` 写；
- 和读线程后续观察到该值的 `volatile` 读；
- 之间存在可见性与顺序保证。

但 Java 内存模型没有承诺下面这整个序列会变成原子事务：

```text
读 -> 基于旧值计算 -> 写回
```

因此，`volatile` 适合做：
- 状态位 / 停止标志；
- 发布标记；
- 一个线程写、其他线程读的场景。

它不适合直接做：
- `count++`
- `count += n`
- `if (count == 0) { count = 1; }`
- 任何“先读，再决定怎么写”的复合更新

### 2.4 正确的并发计数方式

如果需求是“多个线程同时递增计数器，结果不能丢”，应该用能提供原子性或互斥的方案，例如：

```java
AtomicInteger count = new AtomicInteger(0);
count.incrementAndGet();
```

高并发统计场景也常用：

```java
LongAdder count = new LongAdder();
count.increment();
```

或者直接使用 `synchronized` / `Lock` 把临界区保护起来。

一句话总结：

- `volatile` 解决的是“看得见”
- 计数器需要的是“改得完整且不会互相覆盖”
- 前者是可见性，后者是原子性
- `volatile` 只有前者，没有后者

## 3. 可见性：volatile stop flag（为什么能停下来）

对应测试：
- `volatile_stopFlag_shouldBeVisibleToSpinThread`

对应演示类：
- `VolatileStopFlagDemo`

### 测试目的
证明：当 stop flag 是 `volatile` 时，工作线程在自旋循环中能及时看到主线程的更新并退出。

### 测试方案
- 工作线程执行：`while (running) { ... }`
- 主线程在确认工作线程已启动后执行：`running = false`
- 用 `join + timeout` 断言线程能在限定时间内结束（防止卡死）。

### 结论与解释（JMM 视角）
- 对 `volatile` 变量的写入具备“对其他线程可见”的语义。
- 对 `volatile` 变量的读取不能无限期地只用寄存器/CPU cache 中的旧值（需要重新获取可见值）。
- 因此，主线程写入 `running=false` 后，工作线程应该能观察到该变化并退出循环。

补充说明（工程实践）：
- demo 中的循环做了一点无意义计算，是为了降低“空自旋被过度优化”的概率。  
  这不是 `volatile` 的证明点，只是让示例更像真实自旋逻辑。

## 4. 有序性与发布：ready/value 的 publish/consume 模式

对应测试：
- `volatile_readyFlag_shouldPublishPriorWrites_noReorderingAcrossVolatile`

对应演示类：
- `VolatilePublishDemo`

### 测试目的
证明：`volatile` 能提供“发布（publish）保证”：
- 写线程先写入普通变量，再写入 `volatile` 发布标记；
- 读线程读到发布标记后，必须能看到发布之前的普通写入结果。

### 测试方案（逐轮验证）
每一轮 i：
- 写线程：
  1) `value = i`（普通写）
  2) `ready = i`（volatile 写，作为发布标记）
- 读线程：
  1) 自旋直到 `ready == i`（volatile 读）
  2) 读取 `value` 并断言 `value == i`

为了让“每一轮都真的被读线程验证到”，示例里加入了：
- `ack`（volatile）作为握手信号：写线程写完 `ready=i` 后等待 `ack==i`，读线程验证完 `value` 后写 `ack=i`。

### 结论与解释（happens-before / release-acquire）
关键知识点：
- 对同一个 `volatile` 变量：
  - 写线程的 `volatile` 写；
  - 与读线程后续读到该值的 `volatile` 读；
  会形成 happens-before。
- `volatile` 写具备 release 效果：它之前的普通写（例如 `value=i`）不能被重排到 `volatile` 写之后。
- `volatile` 读具备 acquire 效果：它之后的普通读（例如读取 `value`）不能被重排到 `volatile` 读之前。

因此：
- 读线程一旦观察到 `ready == i`，就必须观察到写线程在此之前对 `value` 的写入结果，即 `value == i`。

换句话说：
- `ready` 相当于“发布开关/版本号”，`value` 相当于“被发布的数据”。
- `volatile ready` 把“写入数据”与“发布标记”之间建立了 JMM 级别的顺序与可见性保证。

## 5. 为什么反例默认跳过（@Ignore）

对应测试：
- `nonVolatile_stopFlag_mayNotBeVisibleToSpinThread`（默认跳过）
- `nonVolatile_readyFlag_mayAllowReorderingOrStaleReads`（默认跳过）

反例的价值在于“理解风险”，但它们不适合作为稳定单测：
- 它们属于**概率性现象**：是否出现问题，与 CPU、JIT、运行时负载、具体时序等强相关。
- 在某些环境下可能很难复现；在另一些环境下可能偶尔复现甚至卡住。

所以：
- 我们保留反例（用于学习/演示），但默认 `@Ignore`，避免 `mvn test` 变成“看运气”的构建。

## 6. 如何运行

运行 `concurrency` 模块测试：
- `mvn -pl concurrency -am test`

如果你想尝试反例：
- 去掉 `VolatileVisibilityAndReorderingTest` 中两个反例测试的 `@Ignore` 再跑；
- 复现不了时属于正常现象，可以尝试：
  - 增大循环次数；
  - 更换机器/CPU；
  - 使用不同的 JIT 条件（例如 `-Xcomp`）。
