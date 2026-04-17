# 非 volatile Stop Flag 与 `-Xint`

这篇文档单独整理一个很常见、也最容易被误解的并发问题：

- 为什么下面这种代码有时会停，有时又像死循环一样停不下来
- 为什么加了 `-Xint` 之后，它常常又“看起来恢复正常”
- 为什么真正的修复手段不是 `-Xint`，而是 `volatile` / `synchronized` / `AtomicBoolean` / `interrupt`

如果你想先看 JMM 总览，可以先读 [jmm-notes.md](./jmm-notes.md)。  
如果你想只聚焦 `volatile` 的语义，可以继续看 [volatile-jmm.md](./volatile-jmm.md)。

对应代码位置：

- 演示类：`concurrency/src/main/java/yier/bubu/concurrency/jmm/XintStopFlagDemo.java`
- 测试类：`concurrency/src/test/java/yier/bubu/concurrency/StopFlagVisibilityAndXintTest.java`

## 1. 问题代码长什么样

典型示例就是一个线程不停轮询 stop flag，另一个线程稍后把它改成 `true`：

```java
class Worker {
    boolean stop = false;

    void requestStop() {
        stop = true;
    }

    void run() {
        while (!stop) {
        }
    }
}
```

很多人的直觉是：

- 另一个线程已经执行了 `stop = true`
- 当前线程每一轮都在 `while (!stop)` 里重新判断
- 那它最终总该看到 `true`

问题在于，这个直觉是“按源代码阅读顺序”得出的，不是按 Java 内存模型（JMM）得出的。

## 2. 根因不是“volatile 导致”，而是“没有 happens-before”

如果 `stop` 只是普通 `boolean`，那么：

- 一个线程写 `stop = true`
- 另一个线程读 `stop`

这两者之间没有自动的可见性保证。

也就是说，这段代码存在典型的 **data race（数据竞争）**：

- 有共享变量
- 至少有一个线程在写
- 两个线程之间没有 `volatile`、锁、原子类、`join()` 等同步动作建立 happens-before

一旦进入这种状态，JMM 不保证：

- 读线程能及时看到写线程的新值
- 读线程“最终一定会看到”新值

注意这里的重点不是“可能晚一点看到”，而是：

- **规范上允许它一直看不到**

## 3. 为什么 JIT 可以让它更像“真正的死循环”

很多人最不容易理解的是：明明代码里每轮都写了 `while (!stop)`，为什么还能看起来像“根本不再读取 `stop`”？

原因在于：JIT 只需要保证**单线程语义不变**。

从当前线程自己的视角看，这段代码近似于：

```java
while (!stop) {
}
```

在这个线程内部：

- `stop` 没有被当前线程修改
- 也没有任何同步动作告诉 JVM：“这个值必须作为跨线程通信变量处理”

于是 JIT 可以做更激进的优化，例如把循环理解成近似下面的形式：

```java
boolean cached = stop;
while (!cached) {
}
```

或者更极端地近似成：

```java
if (!stop) {
    while (true) {
    }
}
```

这些优化在这个错误程序上是合法的，因为它没有正确同步。常见可以用来理解的术语有：

- loop invariant code motion
- load hoisting
- read elimination

直白一点说：

- 你想把 `stop` 当“线程间通信开关”
- 但代码没有把它声明成“线程间通信开关”
- JVM 就可以继续把它当“普通字段”优化

## 4. 为什么 `-Xint` 常常能退出

`-Xint` 的作用是：

- 关闭 JIT
- 让 HotSpot 主要以解释执行的方式跑字节码

这会显著减少上面那类激进优化的机会。对这类循环来说，你常会观察到：

- 解释器每轮都重新执行一次字段读取
- 因而更容易在后续某次读到 `stop = true`
- 程序于是退出

所以你看到的现象通常是：

- 默认运行：plain boolean 版本可能卡住
- `-Xint` 运行：plain boolean 版本常常能退出

但这里要非常明确：

- `-Xint` **不是同步机制**
- `-Xint` **没有给普通字段补上 volatile 语义**
- `-Xint` 只是改变了 JVM 的执行方式，让这个错误程序没有被 JIT 放大得那么明显

一句话概括：

- `volatile` 是**规范保证**
- `-Xint` 是**实现现象**

## 5. 为什么这不等于“`-Xint` 修好了程序”

正确的判断标准不是“我这次跑停了”，而是：

- 这段代码是否在 JMM 下被正确同步

plain boolean stop flag 的问题在于：

- 退出与否依赖 CPU、JIT、调度、负载、具体时序
- 即使某次退出了，也不能推出“代码是对的”

`-Xint` 下能退出，最多只能说明：

- 在这个 JVM 配置和这次运行里，解释执行恰好让读线程看到了新值

它不能说明：

- 所有 JVM 都会这样
- 所有运行时条件都这样
- 这个写法已经满足 JMM

## 6. 正确修复方式

### 6.1 `volatile`

如果需求只是传播一个停止标记，最直接的修复是：

```java
volatile boolean stop = false;
```

这时：

- 写线程对 `stop` 的写是 `volatile write`
- 读线程对 `stop` 的读是 `volatile read`
- 对同一个 `volatile` 变量的写，happens-before 后续读

所以 stop flag 能作为线程间通信开关正确工作。

### 6.2 `AtomicBoolean`

如果你希望语义更明确，也可以写成：

```java
AtomicBoolean stop = new AtomicBoolean(false);
```

优点是：

- API 更直观
- 以后如果要做 CAS 更新，也更容易扩展

### 6.3 `synchronized` 或显式锁

如果 stop flag 只是更大临界区的一部分，或者你还要一起保护别的共享状态，那么应使用：

- `synchronized`
- `Lock`

这时重点不再是“单变量可见性”，而是“共享状态的一致性”。

### 6.4 `interrupt`

如果语义是“请求某个线程停下来”，工程上常常更推荐：

```java
thread.interrupt();
```

然后在线程里配合：

```java
while (!Thread.currentThread().isInterrupted()) {
    // work
}
```

因为它更贴近“线程取消/停止请求”的语义。

## 7. 对应示例与测试怎么设计

这次新增的演示类和测试类把现象分成两层：

### 7.1 稳定断言

`StopFlagVisibilityAndXintTest#volatile_mode_shouldStopReliably`

它只验证一件应该稳定成立的事实：

- `volatile` stop flag 在合理超时内应当让工作线程退出

这类断言适合作为 CI 单测。

### 7.2 说明性、默认跳过的实验

测试类里还保留了一个默认 `@Ignore` 的观察性实验：

- `-Xint` + plain boolean 模式下，观察它在某次运行里是否更容易退出

它默认跳过，是因为：

- 这是概率性、实现相关现象
- 不适合写成“必须通过”的稳定测试

## 8. 如何手工运行

先编译并跑测试：

```bash
mvn -pl concurrency -am -Dtest=StopFlagVisibilityAndXintTest -Dsurefire.failIfNoSpecifiedTests=false test
```

如果你想手工对比 plain / volatile / `-Xint`，可以先编译：

```bash
mvn -pl concurrency -am test-compile
```

然后直接运行演示类：

```bash
java -cp concurrency/target/classes yier.bubu.concurrency.jmm.XintStopFlagDemo plain 20 1000
java -Xint -cp concurrency/target/classes yier.bubu.concurrency.jmm.XintStopFlagDemo plain 20 1000
java -cp concurrency/target/classes yier.bubu.concurrency.jmm.XintStopFlagDemo volatile 20 1000
```

输出里会包含：

- `mode=plain` 或 `mode=volatile`
- `interpreterOnly=true/false`
- `happensBeforeGuarantee=true/false`
- `stoppedWithinTimeout=true/false`

其中最重要的是理解：

- `stoppedWithinTimeout=true` 只是一次运行的观察结果
- `happensBeforeGuarantee=true` 才代表这段通信写法在 JMM 下是正确的
