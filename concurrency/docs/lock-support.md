# Java `LockSupport`：`park/unpark`、permit 模型与 AQS 的关系

本文是 `LockSupport` 的专题文档，聚焦更底层的线程阻塞原语本身，重点解释：

- `LockSupport` 到底是不是“锁”
- `park/unpark` 背后的 permit 模型是什么
- permit 在 HotSpot 里到底存放在哪里
- JVM 为什么要提供这套阻塞 / 唤醒原语
- 为什么 `unpark` 可以先于 `park`
- 为什么 `park()` 返回并不代表条件一定成立
- 它和 `wait/notify`、`Condition.await/signal`、AQS 的关系

如果你想先从 JMM、`volatile`、`CAS`、`AQS/ReentrantLock` 的整体关系开始，再回头看 `park/unpark` 这一层，可以先看 [jmm-notes.md](./jmm-notes.md)。

对应代码位置：
- 示例类（最基础的 `LockSupport` 顺序唤醒用法）：`concurrency/src/main/java/yier/bubu/concurrency/AbcPrinters.java`
  - `printByLockSupport(int rounds)`
- 单元测试：`concurrency/src/test/java/yier/bubu/concurrency/AbcPrintersTest.java`

## 1. `LockSupport` 是什么

`LockSupport` 位于 `java.util.concurrent.locks` 包中。

它的核心职责不是“加锁”，而是提供一组**线程阻塞 / 唤醒原语**：

- `park()`：必要时挂起当前线程
- `unpark(thread)`：给目标线程发一个许可，让它可以继续

所以要先把一个常见误区拿掉：

- `LockSupport` **不是一把锁**
- 它不负责互斥
- 它也不直接管理共享状态
- 它只负责把线程“停下来”或者“放过去”

很多 JUC 同步器都会在底层用到它，比如：

- `AbstractQueuedSynchronizer`
- `ReentrantLock`
- `Semaphore`
- `CountDownLatch`

但这些类之所以能正确同步，并不是只靠 `park/unpark`，而是靠：

- `volatile`
- CAS
- 队列协议
- acquire/release 语义

`LockSupport` 只是其中负责“阻塞与唤醒”的那一层。

## 2. permit 模型是什么

理解 `LockSupport`，最关键的是理解它的 **permit（许可）模型**。

可以把它想成：

- 每个线程内部都关联着一个隐藏的 permit
- 这个 permit 只有两种状态：`0` 或 `1`
- `unpark(thread)` 会把目标线程的 permit 设成 `1`
- `park()` 会尝试消费当前线程自己的 permit

于是 `park/unpark` 的核心语义就是：

- 如果当前线程 permit 是 `1`，`park()` 会把它消耗成 `0`，然后**立刻返回**
- 如果当前线程 permit 是 `0`，`park()` 才会进入阻塞

这带来两个非常重要的结论。

### 2.1 `unpark` 可以先于 `park`

例如：

```java
LockSupport.unpark(t);
// ...
LockSupport.park();
```

如果 `park()` 发生时 permit 还在，那么这次 `park()` 会直接返回，不会真的阻塞。

这也是它和 `wait/notify` 的一个本质差别：

- `notify` 先发生，后面才 `wait`，通知可能丢
- `unpark` 先发生，后面才 `park`，这次许可通常不会丢

### 2.2 permit 不会累加

下面这两段代码，对 `t` 来说效果基本一样：

```java
LockSupport.unpark(t);
```

```java
LockSupport.unpark(t);
LockSupport.unpark(t);
```

原因是 permit 最多只有一位，不会从 `1` 再加到 `2`。

一句话记忆：

- `LockSupport` 不是信号量计数器
- 它是“每线程最多只保留 1 个 `0/1` permit 状态”

## 3. 常用 API

最常见的方法有这些：

```java
LockSupport.park();
LockSupport.park(Object blocker);
LockSupport.parkNanos(long nanos);
LockSupport.parkNanos(Object blocker, long nanos);
LockSupport.parkUntil(long deadline);
LockSupport.parkUntil(Object blocker, long deadline);
LockSupport.unpark(Thread thread);
LockSupport.getBlocker(Thread thread);
```

可以按语义记：

- `park()`：无限等待，直到可以继续
- `parkNanos(...)`：相对时间等待
- `parkUntil(...)`：等到某个绝对时间点
- `unpark(thread)`：给指定线程发 permit
- `getBlocker(thread)`：查看该线程最近一次 `park(blocker)` 记录的阻塞原因对象

其中 `blocker` 不是同步条件本身，它更偏向**诊断信息**。

例如：

```java
LockSupport.park(this);
```

这里的 `this` 只是告诉诊断工具：“这个线程是因为这个对象代表的等待场景而被 park 的。”

它不会替你完成条件检查，也不会自动建立锁语义。

## 4. HotSpot 实现层：permit 到底存在哪里

上面讲的“每线程一个 permit”，是 `LockSupport` 对外暴露出来的**抽象语义**。

如果继续往 HotSpot 源码里追，就要把两层分开看：

- 语义层：每个线程最多保留 1 个 permit
- 实现层：普通平台线程把这个 permit 挂在线程关联的 `Parker` 结构上

这里先限定讨论范围：

- 本节说的是 **HotSpot + 普通平台线程**
- 当前 OpenJDK 里的**虚拟线程**已经走 `parkVirtualThread` / `unparkVirtualThread` 这条单独路径
- 所以下面的 `Parker` / `PlatformParker` 说明，不要直接套到虚拟线程实现上

先直接回答实现位置：

- `LockSupport` 是 Java 层 API，它本身不执行 native 阻塞
- `Unsafe.park/unpark` 是 Java 代码进入 JVM native 层的入口
- `Unsafe_Park/Unsafe_Unpark`、`Parker::park/unpark` 属于 HotSpot 的 C++ 实现
- 在 POSIX/Linux 平台上，`Parker` 再通过 `pthread_mutex_t` / `pthread_cond_t` 完成线程等待和唤醒
- `Parker` 不是 Java 类，也不是 JVM 规范要求的公共概念；它是 HotSpot 内部给普通平台线程实现 `park/unpark` 的 native 对象

所以如果把“JVM 内核”理解成 HotSpot VM runtime/native 层，那么可以说 `Parker` 在 JVM 内部；但更准确的说法是：`Parker` 是 HotSpot 这个 JVM 实现里的内部 C++ 结构，其他 JVM 可以用不同结构实现同样的 Java 语义。

### 4.1 从 `LockSupport` 到 `Parker` 的调用链

对于普通平台线程，大致调用链是：

```text
LockSupport.park(...)
-> Unsafe.park(...)
-> Unsafe_Park(...)
-> thread->parker()->park(...)

LockSupport.unpark(thread)
-> Unsafe.unpark(thread)
-> Unsafe_Unpark(...)
-> targetThread->parker()->unpark()
```

这条链说明：

- Java 层没有一个公开的 `permit` 字段
- `LockSupport` 本身也不维护一个全局 permit 表
- 真正保存 permit 状态的，是目标线程对应的 native `Parker`

### 4.2 `Parker` 挂在 `JavaThread` 哪里

在 HotSpot 里，普通 Java 线程对应的是 `JavaThread`。

当前 OpenJDK 的 `JavaThread` 中直接内嵌了一个字段：

```cpp
Parker _parker;
Parker* parker() { return &_parker; }
```

这说明：

- 每个 `JavaThread` 自带一个 `Parker`
- 这个 `Parker` 和线程生命周期绑定
- `park/unpark` 操作的不是某个全局共享 permit，而是**目标线程自己的 `Parker`**

所以如果从“存储位置”来回答 permit 在哪里：

- 它不在 Java 堆上的普通对象字段里
- 它在 HotSpot 里 `JavaThread` 关联的 `Parker` 结构中

### 4.3 POSIX 下 `PlatformParker` 里有哪些字段

在 POSIX 平台上，`Parker` 继承自 `PlatformParker`。后者内部的关键字段可以概括为：

- `volatile int _counter`
- `int _cur_index`
- `pthread_mutex_t _mutex[1]`
- `pthread_cond_t _cond[2]`

这几个字段的职责分别是：

- `_counter`
  这就是 permit 的实际槽位，表示当前线程是否还有一个未消费的 `0/1` permit 状态。
- `_mutex`
  保护慢路径上的状态变更，避免 `park` 和 `unpark` 并发交错时把 `_counter` 和 `_cur_index` 搞乱。
- `_cond[2]`
  真正让线程阻塞 / 唤醒的 POSIX 条件变量。
- `_cur_index`
  表示线程如果真的睡在 condvar 上，当前挂的是哪一个条件变量；`-1` 表示当前并没有在 condvar 上等待。

这里有个很容易混淆的点：

- `Thread.parkBlocker` 不是 permit
- 它只是 Java 层的诊断字段，用来记录“为什么被 park”

真正的 permit 对应的是这里的 `_counter`。

### 4.4 `park()` 是怎么消费 permit 的

POSIX 版 `Parker::park` 的核心逻辑可以压成下面几步。

第一步是**无锁快路径**：

```cpp
if (AtomicAccess::xchg(&_counter, 0) > 0) return;
```

这一步的意思是：

- 原子地把 `_counter` 置成 `0`
- 如果交换前 `_counter > 0`，说明 permit 已经提前到了
- 那这次 `park()` 直接把 permit 消费掉并返回

这就是为什么：

- `unpark` 可以先于 `park`
- 并且这次许可不会丢

第二步是中断优化：

- 如果线程已经有中断挂起，`park()` 直接返回

第三步是进入慢路径：

- 解码时间参数
- 进入 `ThreadBlockInVM`
- 尝试 `pthread_mutex_trylock(_mutex)`

这里不是普通的 `lock`，而是 `trylock`。背后的设计点是：

- 如果这时拿不到 `_mutex`，很可能正好有别的线程在 `unpark` 你
- 既然已经有干扰发生，那就别急着真的睡下去，直接返回即可

拿到锁之后，会再检查一次 `_counter`：

```cpp
if (_counter > 0) {
    _counter = 0;
    // 解锁后返回
    return;
}
```

也就是说：

- 即使你错过了最前面的无锁快路径
- 只要 permit 在“准备睡下去之前”到了
- 这里仍然能把它消费掉并直接返回

只有在这些检查都没拿到 permit 的情况下，线程才真的去等：

- 无限等待走 `pthread_cond_wait`
- 定时等待走 `pthread_cond_timedwait`

在真正睡下去之前，HotSpot 会把 `_cur_index` 设成当前使用的 condvar 编号；醒来后再把它重置回 `-1`。

最后，不管线程是因为哪种原因从 condvar 返回：

- `unpark`
- 中断
- 超时
- 虚假唤醒

都会回到统一收尾逻辑：

- `_cur_index = -1`
- `_counter = 0`
- 解锁并做一次 `OrderAccess::fence()`

所以从实现角度看，`park()` 干的是三件事：

1. 如果有 permit，就消费它
2. 如果没有 permit，才真正睡眠
3. 醒来后把 permit 状态收回到“已消费”的 `0`

### 4.5 `unpark()` 是怎么发 permit 的

POSIX 版 `Parker::unpark` 的核心逻辑更短，但正好解释了 permit 为什么不会累加。

它的大致过程是：

1. 先加锁 `_mutex`
2. 读出旧值 `s = _counter`
3. 直接执行 `_counter = 1`
4. 记录当前 `index = _cur_index`
5. 解锁
6. 如果 `s < 1 && index != -1`，就对对应 condvar 做 `pthread_cond_signal`

这里最重要的是第 3 步：

- 它做的是 `_counter = 1`
- 不是 `_counter++`

所以：

- 没 permit 时，`unpark()` 把 `_counter` 设成 `1`
- 已经有 permit 时，再 `unpark()` 还是 `1`

这就是“permit 最多只有 1 个，不会累加”的底层原因。

第 6 步则解释了为什么 `unpark()` 不一定总要 signal：

- 如果目标线程已经真的睡在 condvar 上了，那么 `index != -1`，需要 signal 把它叫醒
- 如果目标线程此刻根本没睡下去，或者已经在返回路上，那么 `index == -1`
- 这时不 signal 也没关系，因为 permit 已经通过 `_counter = 1` 保存下来了

换句话说：

- `signal` 负责“把已经睡着的线程叫醒”
- `_counter` 负责“把许可状态保存下来”

### 4.6 为什么这套实现不会丢通知

很多人第一次接触 `LockSupport` 时，都会问：

- 为什么 `unpark` 可以先于 `park`
- 它为什么不像 `wait/notify` 那样容易丢通知

答案就在 `_counter` 和“睡前多次检查”这两个点上。

看三种典型竞态就够了。

#### 场景一：`unpark` 明显早于 `park`

- `unpark()` 先把 `_counter` 设为 `1`
- 之后线程才来执行 `park()`
- `park()` 一上来就做 `xchg(&_counter, 0)`
- 看到旧值大于 `0`，直接消费 permit 并返回

所以不会丢。

#### 场景二：`park` 刚过快路径，`unpark` 插进来

- `park()` 最开始看到 `_counter == 0`
- 准备进入慢路径
- 这时另一个线程执行 `unpark()`，把 `_counter` 设成 `1`

接下来有两种结果：

- `park()` 在 `trylock` 时发现被干扰，直接返回
- 或者拿到锁后再次发现 `_counter > 0`，于是消费 permit 返回

还是不会丢。

#### 场景三：线程已经真的睡在 condvar 上

- `unpark()` 把 `_counter` 设成 `1`
- 发现 `_cur_index != -1`
- 于是对对应 condvar 做 `signal`
- 目标线程醒来后统一收尾，把 `_counter` 清回 `0`

这时 permit 既没有丢，也不会保留成多张票。

所以 `LockSupport` 不容易丢通知，不是因为 POSIX condvar 本身天然更强，而是因为：

- permit 状态先保存在 `_counter`
- `park()` 在真正睡前会多次检查它
- `signal` 只是“已经睡着时的唤醒加速”

### 4.7 为什么文档总说 permit 是“抽象语义”

因为 Java 规范对外承诺的是：

- 每线程最多 1 个 permit
- `unpark` 可以先于 `park`
- `park` 可能因为中断或虚假返回而恢复

但它并不要求 JVM 必须用某个固定字段名去实现。

对于当前 HotSpot + POSIX 来说：

- permit 对应的是 `PlatformParker::_counter`

但这依然属于具体 VM 的实现细节。  
分析并发语义时，应该先站在 `LockSupport` 的抽象模型上理解；只有在读 OpenJDK 源码、分析底层竞态或排查 JVM 行为时，才需要追到 `Parker` 这一层。

## 5. 为什么 `park()` 会返回

很多人容易把 `park()` 理解成：

- 我被 `unpark` 了
- 所以条件一定成立了

这是不对的。

`park()` 返回，可能是以下几种原因之一：

- permit 可用了
- 当前线程被中断了
- 定时等待超时了
- 发生了虚假返回（spurious return）

所以 `park()` 的正确用法，和 `wait()` 一样，应该围绕“条件循环”来写，而不是“醒来就直接执行”。

标准写法更接近下面这样：

```java
while (!ready) {
    LockSupport.park(this);
}
```

如果你还要处理中断，通常会写成：

```java
while (!ready) {
    LockSupport.park(this);
    if (Thread.interrupted()) {
        throw new InterruptedException();
    }
}
```

这里的重点不是“被唤醒”，而是：

- 醒来只是获得一次**重新检查条件**的机会
- 条件变量本身才决定线程能不能继续

## 6. `LockSupport` 和 `wait/notify` 有什么区别

它们看起来都能“让线程等一等”，但设计层级并不一样。

| 维度 | `wait/notify` | `LockSupport` |
|---|---|---|
| 绑定对象 | 某个对象的 monitor | 某个线程自身 |
| 是否要求持有锁 | 是，必须先持有 monitor | 否 |
| 错误调用后果 | 未持有 monitor 会抛 `IllegalMonitorStateException` | 不要求 monitor |
| 是否自动释放锁 | `wait()` 会释放 monitor | `park()` 不会释放已有锁 |
| 通知是否容易丢 | `notify` 先于 `wait` 可能丢 | `unpark` 先于 `park` 通常不丢 |
| 中断表现 | 抛 `InterruptedException`，并清除中断标记 | 不抛异常，只是返回 |

最容易出错的是这一条：

- `wait()` 的语义是“释放 monitor，然后进入条件等待”
- `park()` 的语义只是“阻塞当前线程”

所以如果你在持有锁时直接 `park()`，锁通常还在你手里。  
这和 `wait()` 是完全不同的。

## 7. `LockSupport` 和 `Condition.await/signal` 的关系

`Condition` 可以理解成比 `LockSupport` 更高一层的条件等待机制。

对照一下：

| 更高层语义 | 更底层支撑 |
|---|---|
| `condition.await()` | 最终会用到阻塞原语 |
| `condition.signal()` | 最终会用到唤醒原语 |

但 `Condition` 做的事情远不止 `park/unpark`：

1. 把线程放进条件队列
2. 释放关联的锁
3. 阻塞线程
4. 被 `signal` 后转移回同步队列
5. 重新竞争锁
6. 拿到锁后才从 `await()` 返回

而 `LockSupport` 本身只解决其中一小块：

- 怎么把线程挂起
- 怎么把线程唤醒

所以：

- `Condition` 是完整的“条件等待机制”
- `LockSupport` 是更底层的“线程阻塞原语”

## 8. `LockSupport` 和 AQS/ReentrantLock 的关系

`LockSupport` 最经典的使用场景，就是 AQS 这一层。

从 JVM 设计角度看，`park/unpark` 解决的是 JUC 同步器的分工问题：

- Java 层同步器负责共享状态、等待队列和竞争协议，例如 `volatile state`、CAS、`head/tail/waitStatus`
- JVM/native 层负责把线程真正阻塞，并在需要时恢复指定线程
- OS 层负责具体的线程调度、条件变量等待或平台事件等待

如果没有 `park/unpark`，AQS 这类同步器只剩下两类不合适的选择。

第一类是自旋等待：

```text
while (!tryAcquire()) {
    // 一直占用 CPU
}
```

这会浪费 CPU，等待时间稍长就不可接受。

第二类是直接使用 `wait/notify`。它也不适合 AQS：

- `wait/notify` 必须依赖某个对象 monitor
- `wait()` 必须在 `synchronized` 内部调用，并且会释放对应 monitor
- `notify()` 不能精确指定要唤醒哪个线程
- `notify` 先发生、`wait` 后发生时，通知可能丢失
- AQS 的锁状态不是 JVM monitor，而是 Java 层的 `volatile state + CAS + 队列`

所以 AQS 需要的是更底层、更小的一块能力：

```text
Java 层自己维护同步状态和等待队列；
JVM 只提供“阻塞当前线程”和“恢复指定线程”的原语。
```

这也是 `LockSupport` 存在的核心原因。

最关键的竞态窗口是：

```text
线程 B 已经进入 AQS 队列，准备 park
线程 A 正好 unlock，并 unpark(B)
```

如果这次恢复请求没有状态记录，而 B 还没真正睡下去，那么 B 后面再执行 `park()` 时就可能长期阻塞。HotSpot 的 `Parker._counter` 就是在这个窗口里保存一个 `0/1` permit 状态：

```text
unpark(B):
    B 对应的 Parker._counter = 1

B 后面执行 park:
    发现 _counter == 1
    把 _counter 清成 0
    直接返回，不进入 OS 等待
```

因此，JVM 实现这套逻辑的目标不是替 Java 层完成“加锁”，而是给 Java 层同步器提供一个可靠的线程级阻塞 / 唤醒底座。

你可以先把这条链记住：

- `volatile/CAS` 管共享状态
- 队列管等待顺序
- `park/unpark` 管线程阻塞与恢复

以 `AQS` 为例，获取资源的大致流程是：

1. 先尝试 CAS 修改状态
2. 成功就直接返回
3. 失败就把当前线程封装成节点，挂进等待队列
4. 在队列里检查自己是否轮到
5. 如果还轮不到，就 `park()`

释放资源时则大致是：

1. 更新共享状态
2. 判断是否真的释放成功
3. 唤醒后继节点对应的线程

所以一定要分清：

- `park/unpark` 负责“停”和“起”
- 同步正确性依赖的是 `state/head/tail/waitStatus` 这些状态字段及其 `volatile/CAS` 协议

这也是为什么不能把 `LockSupport` 直接等同于“同步机制”。

更准确地说：

- `LockSupport` 是同步器的底层零件
- AQS 才是把这些零件拼成 acquire/release 语义的框架

## 9. 结合本仓库示例看 `LockSupport`

本仓库里的 `AbcPrinters.printByLockSupport(int rounds)` 是一个很典型的“轮流唤醒下一个线程”的例子。

核心代码大意如下：

```java
while (turn.get() != 0) {
    LockSupport.park();
}
out.append('A');
turn.set(1);
LockSupport.unpark(threads[1]);
```

它体现了 `LockSupport` 使用时最重要的几个点：

1. `turn` 才是真正的**条件变量**
2. `park()` 只负责在条件不满足时挂起线程
3. `while` 用来防御虚假返回，以及醒来后条件仍不满足的情况
4. `unpark(nextThread)` 只是让下一个线程有机会继续，不等于“逻辑已经完成切换”

整个 `ABC` 轮转的主线其实就是：

- A 检查是不是轮到自己
- 不是就 `park()`
- 是自己就打印
- 改 `turn`
- `unpark` 下一个线程

另外还有一个容易忽略的启动细节：

```java
LockSupport.unpark(threads[0]); // 启动 A
```

这一步就是先把 A 对应的 permit 状态设为 `1`，让整个链条跑起来。

## 10. 常见误区

### 10.1 `LockSupport` 是一种锁吗

不是。

它不负责互斥，不提供“同一时刻只能一个线程进入临界区”的语义。  
它只是更底层的线程阻塞 / 唤醒工具。

### 10.2 多次 `unpark()` 会累计吗

不会。

permit 只有 `0/1` 两种状态，不会累计成 2 次、3 次许可。

### 10.3 `park()` 返回就说明条件成立了吗

不是。

线程可能是因为：

- 收到 permit
- 被中断
- 超时
- 虚假返回

而恢复执行。

所以返回后必须重新检查条件。

### 10.4 `park()` 会像 `wait()` 一样释放锁吗

不会。

`park()` 不会自动释放当前线程已经持有的 monitor 或 `Lock`。  
如果你需要“释放锁后等待条件”，通常应该优先考虑 `wait()` 或 `Condition.await()` 这类更高层机制。

### 10.5 只写 `park/unpark` 就够了吗

不够。

如果没有额外的共享状态和内存语义保证，你只能做到“线程可能被停住或叫醒”，却不一定能做到：

- 条件判断正确
- 共享变量可见
- 并发时序可靠

因此 `LockSupport` 几乎总要和这些东西一起使用：

- `volatile`
- 原子类
- CAS
- 锁
- 队列协议

## 11. 什么时候适合直接用 `LockSupport`

大多数业务代码里，并不建议把它当首选工具。

更常见也更稳妥的选择通常是：

- `synchronized`
- `ReentrantLock + Condition`
- `Semaphore`
- `CountDownLatch`
- `BlockingQueue`

直接使用 `LockSupport` 更适合这些场景：

- 你在实现自己的同步器
- 你在实现类似 AQS 的等待队列
- 你确实需要线程级别的精细唤醒控制

如果你的目标只是“线程之间按条件协作”，通常应该优先选更高层的并发工具，而不是直接从 `park/unpark` 开始拼。

## 12. 一句话总结

可以把 `LockSupport` 记成下面这句话：

- `LockSupport` 提供的是基于“每线程单 permit”的底层阻塞 / 唤醒原语；`unpark` 可以先于 `park`，但 permit 不累加，而共享状态的正确性仍然要靠 `volatile/CAS/锁/条件循环` 来保证

## 13. 参考资料

- [OpenJDK `LockSupport.java`](https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/concurrent/locks/LockSupport.java)
- [OpenJDK `unsafe.cpp`](https://github.com/openjdk/jdk/blob/master/src/hotspot/share/prims/unsafe.cpp)
- [OpenJDK `javaThread.hpp`](https://github.com/openjdk/jdk/blob/master/src/hotspot/share/runtime/javaThread.hpp)
- [OpenJDK `park.hpp`](https://github.com/openjdk/jdk/blob/master/src/hotspot/share/runtime/park.hpp)
- [OpenJDK `park_posix.hpp`](https://github.com/openjdk/jdk/blob/master/src/hotspot/os/posix/park_posix.hpp)
- [OpenJDK `os_posix.cpp`](https://github.com/openjdk/jdk/blob/master/src/hotspot/os/posix/os_posix.cpp)
