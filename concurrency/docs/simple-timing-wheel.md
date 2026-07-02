# Simple Timing Wheel：单层时间轮、槽位与剩余轮数

本文说明 `yier.bubu.concurrency.timewheel.SimpleTimingWheel` 这个教学版时间轮实现。

它现在是 `timewheel` 包里唯一保留的实现，目标不是提供完整调度器，而是用一个 Java 文件讲清楚时间轮算法的核心：

- tick：时间轮每隔一段时间推进一次
- slot：任务按到期 tick 落到槽位数组
- currentSlot：最近一次已经扫描过的槽位
- remainingRounds：任务还需要等几整圈
- worker：后台线程每次醒来推进一个槽位

它**不**解决这些工程问题：

- 任务取消
- 周期任务
- 层级 overflow 时间轮
- `DelayQueue`
- 外部 `Executor`
- worker 晚醒后的补偿追赶
- 持久化或宕机恢复
- 严格实时调度

如果需要理解一个生产级调度器要补哪些能力，可以把这些缺口当作后续扩展清单。但当前代码故意不做这些事，避免教学入口被工程细节淹没。

对应代码位置：

- `concurrency/src/main/java/yier/bubu/concurrency/timewheel/SimpleTimingWheel.java`
- `concurrency/src/main/java/yier/bubu/concurrency/timewheel/package-info.java`

对应测试：

- `concurrency/src/test/java/yier/bubu/concurrency/timewheel/SimpleTimingWheelTest.java`

## 1. 基本用法

```java
SimpleTimingWheel wheel = new SimpleTimingWheel(100L, 8);
wheel.start();

wheel.schedule(() -> {
    System.out.println("run after about 300ms");
}, 300L);

wheel.stop();
```

构造参数含义：

- `tickMillis`：每个 tick 的时长，必须大于 0
- `wheelSize`：槽位数量，必须大于 0

公开方法很少：

| 方法 | 含义 |
| --- | --- |
| `start()` | 启动后台 worker，开始推进 tick |
| `schedule(Runnable, long delayMillis)` | 把任务放入某个未来槽位 |
| `stop()` | 停止 worker，不保证清空未到期任务 |
| `close()` | 调用 `stop()` |

`schedule(...)` 返回 `void`。教学版不支持取消，所以没有返回任务句柄。

## 2. 时间轮模型

可以把单层时间轮看成一个环形数组：

```text
slot:        0    1    2    3    4    5    6    7
           [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]  [ ]
currentSlot = 0
```

这里的 `currentSlot` 表示“最近一次已经扫描过的槽位”。worker 下一次 tick 会扫描：

```text
nextSlot = (currentSlot + 1) % wheelSize
```

如果 `tickMillis = 100`，`wheelSize = 8`，时间轮一圈覆盖：

```text
100ms * 8 = 800ms
```

小于等于一圈的任务可以只靠槽位决定何时执行。超过一圈的任务就需要额外记录还要等几整圈，这就是 `remainingRounds`。

## 3. delay 如何转换成 ticks

教学版使用向上取整，保证任务不会因为取整而早于请求延迟进入槽位：

```java
static long ticksForDelay(long delayMillis, long tickMillis) {
    if (delayMillis == 0) {
        return 1L;
    }
    return ((delayMillis - 1L) / tickMillis) + 1L;
}
```

例如 `tickMillis = 100`：

| delayMillis | ticks | 说明 |
| --- | ---: | --- |
| `0` | `1` | 0 延迟也放到下一个 tick |
| `1` | `1` | 不足 1 个 tick，按 1 个 tick |
| `100` | `1` | 正好 1 个 tick |
| `101` | `2` | 超过 1 个 tick，向上取整 |
| `150` | `2` | 向上取整到 2 个 tick |

这里特意把 `0` 延迟也放到下一个 tick 执行，而不是在调用 `schedule(...)` 的线程里立刻执行。这样所有任务都走同一条教学路径：

```text
schedule -> 入槽 -> tick 推进 -> worker 执行
```

## 4. ticks 如何映射到槽位和剩余轮数

核心计算在 `position(...)`：

```java
static TimeoutPosition position(int currentSlot, int wheelSize, long ticks) {
    int slot = (int) ((currentSlot + (ticks % wheelSize)) % wheelSize);
    long remainingRounds = (ticks - 1L) / wheelSize;
    return new TimeoutPosition(slot, remainingRounds);
}
```

关键点是 `remainingRounds = (ticks - 1) / wheelSize`，而不是 `ticks / wheelSize`。

假设：

```text
currentSlot = 0
wheelSize = 8
```

那么：

| ticks | target slot | remainingRounds | 什么时候执行 |
| ---: | ---: | ---: | --- |
| `1` | `1` | `0` | 下一个 tick 扫描 slot 1 时执行 |
| `8` | `0` | `0` | 转一圈回到 slot 0 时执行 |
| `9` | `1` | `1` | 第一次扫 slot 1 时减轮数，第二次扫 slot 1 时执行 |

这个 `(ticks - 1) / wheelSize` 是教学版里最容易出错的 off-by-one 点。

如果用 `ticks / wheelSize`：

- `ticks = 8` 会得到 `remainingRounds = 1`
- 任务落到当前 slot 0
- worker 转一圈回到 slot 0 时只会把 rounds 从 1 减到 0
- 任务要再等一整圈才执行

这会比预期多等一圈。

## 5. worker 如何推进时间

worker 主循环很小：

```text
while running:
  parkNanos(tickNanos)
  if stopped:
    exit
  advanceOneTick()
  run due tasks
```

代码使用 `LockSupport.parkNanos(...)` 等待一个 tick，`stop()` 使用 `LockSupport.unpark(...)` 唤醒 worker 退出。

这里选择 `LockSupport` 是为了贴近 Java 并发底层原语，也能和 [LockSupport 专题](./lock-support.md) 对照阅读。

需要注意：`parkNanos(...)` 不是严格定时器。它可能因为操作系统调度、JVM safepoint、CPU 负载等原因晚醒，也可能被提前唤醒。教学版不做补偿，每次从等待中返回只推进一个槽位。

## 6. 扫描槽位时发生什么

每次 tick 到来，worker 调用 `advanceOneTick()`：

```text
currentSlot = (currentSlot + 1) % wheelSize
扫描 buckets[currentSlot]
  remainingRounds > 0:
    remainingRounds--
  remainingRounds == 0:
    从桶里移除，加入 dueTasks
释放锁
逐个执行 dueTasks
```

任务执行不在锁内进行。这样即使用户任务很慢，也不会在执行期间持有桶结构锁。

但用户任务仍然运行在 worker 线程里。一个任务执行太久，会拖慢后续 tick。这是教学版的重要限制。

## 7. 为什么还需要 ReentrantLock

虽然这是教学版，也至少有两个线程会同时碰时间轮结构：

- 调用方线程：调用 `schedule(...)`，往某个桶里放任务
- worker 线程：推进 tick，扫描当前桶，移除到期任务

所以 `SimpleTimingWheel` 使用一把 `ReentrantLock` 保护这些共享状态：

- `currentSlot`
- `buckets`
- `running`
- `started`
- `workerThread`

锁的使用保持朴素：

```java
lock.lock();
try {
    // mutate wheel state
} finally {
    lock.unlock();
}
```

这里没有使用 CAS、无锁队列或多个条件变量，因为那些不是这篇文档要讲的重点。

## 8. 生命周期语义

`SimpleTimingWheel` 的生命周期刻意简单：

- 构造方法只初始化桶，不启动线程。
- 必须显式调用 `start()`。
- `start()` 只能调用一次。
- `stop()` 设置停止标记，并 `unpark` worker。
- `stop()` 后不支持重新 `start()`。
- 未 `start()` 或已 `stop()` 时调用 `schedule(...)` 会抛 `IllegalStateException`。

停止不是优雅 drain：

- 已经在桶里但未到期的任务不会继续执行。
- 已经被 worker 取出并准备执行的任务仍可能执行。
- 不等待 worker 完全退出。

这些取舍都是为了让示例保持短小。完整调度器需要更细的 shutdown 语义。

## 9. 异常处理

worker 执行任务时会捕获 `Throwable`：

```java
try {
    task.runnable.run();
} catch (Throwable t) {
    t.printStackTrace();
}
```

这样一个任务抛异常不会杀死 worker，也不会阻止后续任务执行。

这不是生产级异常处理策略。真实系统通常需要日志、指标、告警，或者调用方传入异常处理器。

## 10. 当前实现边界

这个版本只适合教学和小实验，边界要明确：

- 不支持取消任务。
- 不支持 fixed-rate 或 fixed-delay 周期任务。
- 不支持超过一层时间轮后的更粗粒度 overflow。
- 不根据真实 elapsed time 补偿 worker 晚醒。
- 不接收外部 `Executor`，任务直接在 worker 线程运行。
- 不保证严格准时执行。
- 不保证 `stop()` 后清空任务。
- 不做持久化，JVM 退出后未执行任务全部丢失。

如果要扩展成更完整的调度器，通常会继续引入：

- 取消句柄
- 任务状态
- 外部执行器
- 层级时间轮
- `DelayQueue` 或更精确的等待机制
- 明确的 shutdown 和 drain 策略

## 11. 推荐阅读顺序

建议按这个顺序读代码和测试：

1. 读 `SimpleTimingWheelTest.ticksForDelay_shouldRoundUpAndRunZeroDelayOnNextTick`，先理解 delay 到 ticks 的转换。
2. 读 `SimpleTimingWheelTest.position_shouldComputeSlotAndRemainingRounds`，看懂 slot 和 rounds。
3. 读 `SimpleTimingWheel.schedule(...)`，理解任务如何入槽。
4. 读 `SimpleTimingWheel.advanceOneTick()`，理解 worker 如何扫描槽位。
5. 读 `SimpleTimingWheel.runLoop()` 和 `stop()`，理解 `LockSupport.parkNanos/unpark` 的用法。
6. 最后读生命周期和 smoke test，确认这个实现的使用边界。
