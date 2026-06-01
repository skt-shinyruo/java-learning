# CountDownLatch、CyclicBarrier、Semaphore：三种常用 JUC 同步工具

本文说明 `java.util.concurrent` 里三个很容易混淆的同步工具：

- `CountDownLatch`
- `CyclicBarrier`
- `Semaphore`

对应代码位置：

- 示例类：`concurrency/src/main/java/yier/bubu/concurrency/JucSynchronizersDemo.java`
- 单元测试：`concurrency/src/test/java/yier/bubu/concurrency/JucSynchronizersDemoTest.java`

## 1. 总览

| 工具 | 解决的问题 | 典型模型 | 是否可复用 |
| --- | --- | --- | --- |
| `CountDownLatch` | 一个或多个线程等待一组任务完成 | 倒计时门闩 | 否 |
| `CyclicBarrier` | 一组线程互相等待，到齐后一起进入下一阶段 | 集合点 / 屏障 | 是 |
| `Semaphore` | 限制同一时间最多有多少线程访问资源 | 许可证 | 是 |

一句话区分：

```text
CountDownLatch：等别人做完
CyclicBarrier：大家互相等，到齐再走
Semaphore：限制最多多少人能进
```

## 2. CountDownLatch：倒计时门闩

`CountDownLatch` 适合“主线程等待多个子任务完成”的场景。

核心方法：

```java
CountDownLatch latch = new CountDownLatch(3);

latch.countDown(); // 计数减 1
latch.await();     // 等计数归零
```

它的内部有一个计数器：

- 构造时指定初始计数
- 工作线程完成任务后调用 `countDown()`
- 等待线程调用 `await()`，直到计数变成 `0`
- 计数归零后，所有等待线程都会被放行

示例代码里的方法：

```java
JucSynchronizersDemo.waitForWorkersWithCountDownLatch(4);
```

这个示例启动 4 个工作任务，每个任务完成后调用一次 `countDown()`，主线程通过 `await()` 等待所有任务结束，最后返回完成数量。

### 2.1 适合场景

- 主线程等待多个初始化任务完成
- 测试代码里等待异步任务结束
- 服务启动时等待多个依赖准备好
- 并发任务完成后统一汇总结果

### 2.2 注意点

`CountDownLatch` 是一次性的。

计数归零以后不能重置。如果需要一轮又一轮地等待同一批线程到达某个阶段，应考虑 `CyclicBarrier` 或 `Phaser`。

## 3. CyclicBarrier：循环屏障

`CyclicBarrier` 适合“一组线程在同一个阶段末尾互相等待”的场景。

核心方法：

```java
CyclicBarrier barrier = new CyclicBarrier(3);

barrier.await(); // 当前线程到达屏障，等待其他线程
```

当到达屏障的线程数达到构造时指定的 `parties` 后：

- 所有等待线程一起继续执行
- 屏障自动进入下一轮
- 如果配置了 barrier action，会在放行前执行一次

示例代码里的方法：

```java
JucSynchronizersDemo.synchronizePhasesWithCyclicBarrier(3, 2);
```

这个示例启动 3 个线程，执行 2 个阶段。每个线程每阶段到达一次屏障，所以总到达次数是 `3 * 2 = 6`；barrier action 每阶段执行一次，所以完成阶段数是 `2`。

### 3.1 适合场景

- 多线程分阶段计算
- 每一轮都要等所有线程完成后再进入下一轮
- 并行任务需要阶段性汇合

### 3.2 注意点

`CyclicBarrier` 强调“线程之间互相等待”。

如果某个线程在等待中被中断、超时，或者 barrier action 抛异常，屏障可能进入 broken 状态，其他等待线程会收到异常。真实业务里要明确处理失败策略，避免一组线程永久等待。

## 4. Semaphore：信号量

`Semaphore` 适合“限制并发访问数量”的场景。

核心方法：

```java
Semaphore semaphore = new Semaphore(2);

semaphore.acquire();
try {
    // 最多 2 个线程能同时进入这里
} finally {
    semaphore.release();
}
```

它的内部维护一组许可证：

- `acquire()` 获取一个许可证，没有许可证时阻塞
- `release()` 归还一个许可证
- 许可证数量决定同时能进入临界区的线程数量

示例代码里的方法：

```java
JucSynchronizersDemo.limitConcurrentAccessWithSemaphore(8, 2);
```

这个示例启动 8 个任务，但信号量只有 2 个许可证，所以同一时间最多 2 个任务能进入受保护区域。方法返回任务完成数量和实际观察到的最大并发数。

### 4.1 适合场景

- 接口限流
- 数据库连接池
- 文件句柄、网络连接等稀缺资源控制
- 控制某段代码的最大并发量

### 4.2 注意点

`acquire()` 和 `release()` 要成对出现，通常写成：

```java
semaphore.acquire();
try {
    doWork();
} finally {
    semaphore.release();
}
```

否则一旦业务代码抛异常，许可证可能不会归还，后续线程会被错误地阻塞。

## 5. 三者怎么选

如果你要表达的是“等 N 件事都做完”，优先选 `CountDownLatch`。

如果你要表达的是“N 个线程每一阶段都要到齐后才能继续”，优先选 `CyclicBarrier`。

如果你要表达的是“最多允许 N 个线程同时访问某个资源”，优先选 `Semaphore`。

## 6. 和锁的关系

这三个工具都不是普通意义上的互斥锁。

- `CountDownLatch` 负责等待完成信号，不负责保护共享变量
- `CyclicBarrier` 负责阶段对齐，不负责互斥访问
- `Semaphore` 可以限制并发量，但不等同于 `synchronized` 或 `ReentrantLock`

如果要保护共享可变状态，仍然需要根据场景选择 `synchronized`、`ReentrantLock`、原子类、并发容器等机制。
