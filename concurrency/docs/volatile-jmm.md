# volatile：可见性与禁止重排（JMM 视角）

本文档配合 `concurrency` 模块中的示例与测试，说明 `volatile` 在 Java 内存模型（JMM）下的核心语义：**可见性**与**有序性（通过 happens-before / release-acquire 语义形成的重排约束）**。

对应代码位置：
- 单元测试（带结论断言 + 中文说明）：`concurrency/src/test/java/yier/bubu/concurrency/VolatileVisibilityAndReorderingTest.java`
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

## 2. 可见性：volatile stop flag（为什么能停下来）

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

## 3. 有序性与发布：ready/value 的 publish/consume 模式

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

## 4. 为什么反例默认跳过（@Ignore）

对应测试：
- `nonVolatile_stopFlag_mayNotBeVisibleToSpinThread`（默认跳过）
- `nonVolatile_readyFlag_mayAllowReorderingOrStaleReads`（默认跳过）

反例的价值在于“理解风险”，但它们不适合作为稳定单测：
- 它们属于**概率性现象**：是否出现问题，与 CPU、JIT、运行时负载、具体时序等强相关。
- 在某些环境下可能很难复现；在另一些环境下可能偶尔复现甚至卡住。

所以：
- 我们保留反例（用于学习/演示），但默认 `@Ignore`，避免 `mvn test` 变成“看运气”的构建。

## 5. 如何运行

运行 `concurrency` 模块测试：
- `mvn -pl concurrency -am test`

如果你想尝试反例：
- 去掉 `VolatileVisibilityAndReorderingTest` 中两个反例测试的 `@Ignore` 再跑；
- 复现不了时属于正常现象，可以尝试：
  - 增大循环次数；
  - 更换机器/CPU；
  - 使用不同的 JIT 条件（例如 `-Xcomp`）。

