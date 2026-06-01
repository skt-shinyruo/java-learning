# `synchronized` 与 `ReentrantLock` 底层对比

这篇文档整理 `synchronized`、`ObjectMonitor`、`wait/notify`、`ReentrantLock`、AQS 和 `LockSupport` 之间的关系。

核心结论先放在前面：

- `synchronized` 是 JVM 内置锁，语义由 Java 语言和 JVM 规范定义，HotSpot 中主要由 C++、JIT 生成代码和少量平台相关逻辑实现。
- `ReentrantLock` 是 JDK 类库锁，主体逻辑在 Java 层，核心基于 AQS、CAS、同步队列和 `LockSupport.park/unpark`。
- 两者都能提供互斥、可重入和内存可见性保证，但它们不是同一把锁，也不共享底层队列。

相关延伸文档：

- [`synchronized` 全面整理](./synchronized-notes.md)
- [`wait/notify/notifyAll`](./wait-notify.md)
- [`LockSupport`](./lock-support.md)
- [JMM Notes](./jmm-notes.md)

---

## 1. 一句话区分

可以粗略记成：

```text
synchronized = JVM 内置 monitor 锁
ReentrantLock = JDK 类库基于 AQS 实现的显式锁
```

更准确地说：

```text
ReentrantLock 是 JDK 在 Java 层实现的一套显式锁机制。
它在语义上和 synchronized 有相似点，比如互斥、可重入、可见性保证；
但它不是 JVM monitor，底层不是 ObjectMonitor，而是 AQS + CAS + LockSupport。
```

对照如下：

| 维度 | `synchronized` | `ReentrantLock` |
|---|---|---|
| 所在层级 | JVM 内置机制 | JDK 类库机制 |
| 入口形式 | 关键字 / 方法标志 | 普通 Java 对象方法 |
| 编译形态 | `monitorenter` / `monitorexit` 或 `ACC_SYNCHRONIZED` | 普通方法调用 |
| 核心结构 | 对象头、Mark Word、monitor、`ObjectMonitor` | AQS `state`、同步队列、`Condition` 队列 |
| 阻塞唤醒 | JVM monitor 逻辑，最终依赖 OS 能力 | `LockSupport.park/unpark`，最终依赖 JVM/native/OS |
| 条件等待 | `wait/notify/notifyAll` | `Condition.await/signal/signalAll` |
| 公平策略 | 不提供公平锁语义 | 可选择公平锁或非公平锁 |
| 超时/可中断获取锁 | 不支持 | 支持 `tryLock`、`lockInterruptibly` |

---

## 2. `synchronized` 的执行链路

同步代码块：

```java
synchronized (lock) {
    doSomething();
}
```

编译后会出现类似字节码：

```text
monitorenter
...
monitorexit
```

同步方法：

```java
public synchronized void test() {
}
```

通常不会在方法体里显式生成 `monitorenter` / `monitorexit`，而是在方法访问标志上带：

```text
ACC_SYNCHRONIZED
```

运行时链路可以简化成：

```text
Java synchronized
  -> javac 编译成 monitorenter/monitorexit 或 ACC_SYNCHRONIZED
  -> JVM 解释器或 JIT 执行同步语义
  -> HotSpot 结合对象头 Mark Word 处理锁状态
  -> 竞争激烈或必须走 monitor 时膨胀为 ObjectMonitor
```

在 HotSpot 中，并不是每次进入 `synchronized` 都直接使用重量级 `ObjectMonitor`。常见经典模型里，锁状态可能经历：

```text
无锁 -> 偏向锁 -> 轻量级锁 -> 重量级锁
```

其中重量级锁阶段才会使用 `ObjectMonitor` 来维护 owner、重入次数、等待队列和唤醒逻辑。

版本背景需要注意：

- JDK 8 语境下，很多资料会详细讲偏向锁、轻量级锁、重量级锁。
- JDK 15 之后偏向锁默认关闭并逐步退出主线。
- 新版本 HotSpot 的锁实现细节还在演进，但 monitor 语义没有变。

---

## 3. `ObjectMonitor` 维护什么

当锁膨胀为重量级 monitor 时，HotSpot 会为对象关联 JVM 内部的 `ObjectMonitor`。

可以把它简化理解成：

```cpp
class ObjectMonitor {
    void* volatile _owner;        // 当前持有 monitor 的线程
    intptr_t _recursions;         // 重入次数
    ObjectWaiter* _EntryList;     // 等待进入 monitor 的线程
    ObjectWaiter* _cxq;           // 新竞争线程的队列，HotSpot 内部优化用
    ObjectWaiter* _WaitSet;       // 调用了 wait() 后等待条件的线程
};
```

真实源码会更复杂，字段名在不同 JDK 版本中也可能变化。这里关注的是理解模型。

几个核心角色：

| 字段 | 含义 |
|---|---|
| `_owner` | 当前持有这把 monitor 的线程 |
| `_recursions` | 同一线程重入这把 monitor 的次数 |
| `_EntryList` | 已经有资格竞争 monitor，但还没拿到锁的线程 |
| `_cxq` | 新进入竞争的线程队列，后续会和 entry 队列协同 |
| `_WaitSet` | 已经拿到过锁，但调用 `wait()` 释放锁后等待条件的线程 |

最重要的区分是：

```text
EntryList / cxq 等的是锁
WaitSet 等的是条件通知
```

---

## 4. `monitorenter` 的主线

当线程执行：

```java
synchronized (lock) {
}
```

进入 `monitorenter` 时，重量级 monitor 下可以简化为：

```text
1. 如果 _owner == null
   当前线程尝试成为 owner

2. 如果 _owner == 当前线程
   说明是重入，_recursions++

3. 如果 _owner 是其他线程
   当前线程进入竞争队列，等待之后被唤醒
```

例如 T1 第一次进入：

```text
_owner = T1
_recursions = 0
```

T1 再次进入同一把锁：

```text
_owner = T1
_recursions = 1
```

这就是 `synchronized` 可重入的底层原因：monitor 不只记录“锁有没有被占用”，还记录当前 owner 和重入层数。

如果 T2、T3 此时也想进入同一个 `synchronized(lock)`：

```text
_owner    = T1
_EntryList/_cxq = T2, T3
_WaitSet  = empty
```

这些线程在 Java 线程 dump 中通常表现为：

```text
BLOCKED
waiting to lock monitor
```

---

## 5. `monitorexit` 的主线

线程退出同步块时，执行 `monitorexit`。

简化逻辑：

```text
1. 如果 _owner 不是当前线程
   抛 IllegalMonitorStateException

2. 如果 _recursions > 0
   _recursions--
   还没有真正释放 monitor

3. 如果 _recursions == 0
   清空 _owner
   从 EntryList/cxq 中选择候选线程唤醒
```

要注意：

```text
被唤醒不等于已经拿到锁。
```

`monitorexit` 之后，候选线程只是有机会继续竞争 monitor。`synchronized` 不提供严格公平语义，新来的线程也可能参与竞争。

---

## 6. `wait()`、`notify()` 与 `ObjectMonitor`

`wait/notify/notifyAll` 不是普通 Java 代码实现的逻辑。

在 Java 层，它们是 `Object` 上的 native 方法：

```java
public final native void wait(long timeout) throws InterruptedException;
public final native void notify();
public final native void notifyAll();
```

它们操作的是对象关联的 monitor 和 wait set。

### 6.1 `wait()` 做了什么

前提：

```java
synchronized (lock) {
    lock.wait();
}
```

当前线程必须已经持有 `lock` 的 monitor，否则会抛：

```text
IllegalMonitorStateException
```

调用 `wait()` 时，可以简化为：

```text
1. 检查当前线程是不是 _owner
2. 把当前线程加入 _WaitSet
3. 完整释放 monitor，包括重入层数
4. 当前线程进入 WAITING / TIMED_WAITING
5. 被 notify / notifyAll / interrupt / timeout / spurious wakeup 唤醒
6. 从 WaitSet 转回锁竞争路径
7. 重新竞争并重新获得同一个 monitor
8. 恢复必要的重入状态
9. wait() 返回，或者在重新获得锁后抛 InterruptedException
```

关键语义：

```text
wait 会释放锁。
wait 返回前必须重新拿回同一把锁。
```

### 6.2 `notify()` 做了什么

当前线程也必须已经持有 monitor：

```java
synchronized (lock) {
    lock.notify();
}
```

简化逻辑：

```text
1. 检查当前线程是不是 _owner
2. 从 _WaitSet 中选择一个等待线程
3. 把它转移到 EntryList/cxq
4. 让它之后有资格重新竞争 monitor
```

注意：

```text
notify 不会释放锁。
notify 也不会让等待线程立刻继续执行。
```

等待线程必须等当前持锁线程退出同步块之后，才可能重新获得 monitor，然后从 `wait()` 返回。

### 6.3 `notifyAll()` 做了什么

`notifyAll()` 可以理解为：

```text
把 _WaitSet 中的所有线程都转移到 EntryList/cxq，
让它们都有资格重新竞争 monitor。
```

但它们不会并行通过临界区，最终仍然是一条线程一条线程地重新拿锁。

---

## 7. 一个完整时序

线程 T1：

```java
synchronized (lock) {
    lock.wait();
}
```

线程 T2：

```java
synchronized (lock) {
    lock.notify();
}
```

时序可以压缩成：

```text
T1 进入 synchronized
_owner = T1

T1 调用 wait()
T1 -> _WaitSet
_owner = null

T2 进入 synchronized
_owner = T2

T2 调用 notify()
T1 从 _WaitSet 转移到 EntryList/cxq
_owner 仍然是 T2

T2 退出 synchronized
_owner = null

T1 重新竞争 monitor 成功
_owner = T1

T1 的 wait() 返回
T1 继续执行 synchronized 中 wait() 后面的代码

T1 退出 synchronized
_owner = null
```

最容易误解的点：

```text
wait 会释放锁。
notify 不会释放锁。
notify 只是把线程从 WaitSet 转移到重新竞争锁的路径。
wait 返回前必须重新拿到锁。
```

---

## 8. 这些逻辑是不是 JVM 内部实现

是的。`ObjectMonitor`、`EntryList`、`WaitSet`、monitor 膨胀、`monitorenter` / `monitorexit` 的慢路径，都是 JVM 内部实现细节。

以 HotSpot 为例，主要由 C++、JIT 生成代码和少量平台相关逻辑实现。

相关源码位置可以从这些文件开始看：

JDK 8：

```text
hotspot/src/share/vm/runtime/objectMonitor.hpp
hotspot/src/share/vm/runtime/objectMonitor.cpp
hotspot/src/share/vm/runtime/synchronizer.cpp
hotspot/src/share/vm/runtime/synchronizer.hpp
hotspot/src/share/vm/oops/markOop.hpp
```

现代 JDK：

```text
src/hotspot/share/runtime/objectMonitor.hpp
src/hotspot/share/runtime/objectMonitor.cpp
src/hotspot/share/runtime/synchronizer.cpp
src/hotspot/share/oops/markWord.hpp
```

链路可以粗略理解为：

```text
Java synchronized
  -> javac 生成 monitorenter/monitorexit 或 ACC_SYNCHRONIZED
  -> JVM 解释器或 JIT 执行同步语义
  -> HotSpot fast path 尝试轻量级进入
  -> slow path 进入 ObjectSynchronizer / ObjectMonitor
  -> 必要时调用 OS 线程阻塞/唤醒能力
```

不过要区分规范和实现：

- Java/JVM 规范定义的是 `synchronized`、monitor、`wait/notify` 的语义。
- `ObjectMonitor`、具体字段、队列策略、锁膨胀细节，是 HotSpot 的实现。
- 其他 JVM 可以用不同内部结构实现相同语义。

---

## 9. `ReentrantLock` 的执行链路

`ReentrantLock` 属于 JDK 类库：

```java
ReentrantLock lock = new ReentrantLock();

lock.lock();
try {
    doSomething();
} finally {
    lock.unlock();
}
```

它的核心在：

```text
java.util.concurrent.locks.ReentrantLock
java.util.concurrent.locks.AbstractQueuedSynchronizer
```

可以把 AQS 中的 `state` 理解成锁状态：

```text
state = 0   没有线程持有锁
state = 1   某线程持有锁一次
state = 2   同一个线程重入两次
...
```

加锁主线：

```text
1. 尝试 CAS 把 state 从 0 改成 1
2. 成功则设置 exclusiveOwnerThread 为当前线程
3. 如果 owner 是当前线程，说明重入，state++
4. 如果失败，把当前线程包装成 AQS 节点，加入同步队列
5. 轮不到自己时，通过 LockSupport.park() 挂起
6. 前驱节点释放后，通过 unpark 被唤醒
7. 醒来后重新尝试获取锁
```

释放主线：

```text
1. 检查当前线程是不是 owner
2. state--
3. 如果 state 仍大于 0，说明只是退出一层重入
4. 如果 state 变成 0，清空 owner
5. 唤醒 AQS 队列中的后继节点
```

这和 `ObjectMonitor` 很像，都要处理：

- owner
- 重入次数
- 等待队列
- 阻塞和唤醒

但实现结构完全不同：

```text
synchronized:
  owner/队列主要在 JVM monitor / ObjectMonitor 内部

ReentrantLock:
  owner/state/队列主要在 Java 层 AQS 内部
```

---

## 10. `Condition` 与 `WaitSet` 的对应关系

`wait/notify` 和 `Condition.await/signal` 都是条件等待机制。

对应关系：

| monitor 机制 | AQS/Lock 机制 |
|---|---|
| `synchronized(lock)` | `reentrantLock.lock()` |
| `lock.wait()` | `condition.await()` |
| `lock.notify()` | `condition.signal()` |
| `lock.notifyAll()` | `condition.signalAll()` |
| monitor 的 `WaitSet` | `ConditionObject` 条件队列 |
| monitor 的 entry 队列 | AQS 同步队列 |

`Condition.await()` 大致做的事情：

```text
1. 当前线程必须持有 ReentrantLock
2. 加入 Condition 条件队列
3. 完整释放 ReentrantLock，包括重入层数
4. park 当前线程
5. 被 signal / signalAll / interrupt / timeout 唤醒
6. 从条件队列转入 AQS 同步队列
7. 重新竞争 ReentrantLock
8. 重新拿到锁后 await() 返回
```

这和 `wait()` 的语义高度相似。

区别在于，一把 `ReentrantLock` 可以创建多个 `Condition`：

```java
ReentrantLock lock = new ReentrantLock();
Condition notFull = lock.newCondition();
Condition notEmpty = lock.newCondition();
```

这意味着它可以有多个条件队列：

```text
AQS 同步队列
Condition notFull 条件队列
Condition notEmpty 条件队列
```

而 `synchronized + wait/notify` 通常围绕一个对象 monitor 的 wait set 协作，条件队列区分能力较弱。

---

## 11. 一个重要误区：`synchronized(lock)` 和 `lock.lock()` 不是同一把锁

这段代码锁的是 `lock` 这个对象的 JVM monitor：

```java
ReentrantLock lock = new ReentrantLock();

synchronized (lock) {
    // JVM monitor
}
```

这段代码锁的是 `ReentrantLock` 内部 AQS 状态：

```java
lock.lock();
try {
    // AQS state
} finally {
    lock.unlock();
}
```

它们没有互斥关系。

也就是说：

```text
synchronized(lock)
```

和：

```text
lock.lock()
```

不是同一套锁机制。

同理，`wait/notify` 只属于 JVM monitor：

```java
synchronized (lockObject) {
    lockObject.wait();
}
```

`ReentrantLock` 对应的是 `Condition`：

```java
lock.lock();
try {
    condition.await();
} finally {
    lock.unlock();
}
```

不要在 `lock.lock()` 之后直接调用 `lock.wait()` 来表达条件等待。那操作的是 `ReentrantLock` 对象本身的 JVM monitor，和 AQS 锁不是一回事。

---

## 12. 内存语义

两者都能建立 happens-before 关系。

`synchronized`：

```text
对同一个 monitor 的 monitorexit happens-before 后续 monitorenter
```

`ReentrantLock`：

```text
unlock happens-before 后续同一把 Lock 的 lock
```

所以它们都能保证：

```text
释放锁前的写入，对随后获得同一把锁的线程可见。
```

这也是为什么它们都能用于保护共享变量。

---

## 13. 线程状态上的差异

竞争 `synchronized` 失败时，线程通常表现为：

```text
BLOCKED
waiting to lock monitor
```

因为它等待的是 JVM monitor。

竞争 `ReentrantLock` 失败后，线程通常是被 `LockSupport.park()` 挂起，线程状态更常见为：

```text
WAITING
TIMED_WAITING
```

这在分析线程 dump 时很有用。

---

## 14. 什么时候用哪个

简单互斥优先用：

```java
synchronized (lock) {
}
```

原因：

- 代码少
- 自动释放锁
- 异常路径不容易写错
- JVM 对常见场景已经做了大量优化

需要这些能力时，再考虑 `ReentrantLock`：

- 尝试获取锁：`tryLock()`
- 超时获取锁：`tryLock(timeout, unit)`
- 可中断获取锁：`lockInterruptibly()`
- 公平锁：`new ReentrantLock(true)`
- 多个条件队列：`newCondition()`
- 更丰富的锁状态观察 API

不要单纯因为“性能”选择 `ReentrantLock`。现代 JVM 下，`synchronized` 的优化已经很多，真正的选择依据应该是语义能力。

