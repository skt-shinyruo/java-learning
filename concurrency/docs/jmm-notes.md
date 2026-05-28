# Java Memory Model（JMM）全面整理

这份文档把前面讨论过的内容整理成一条完整主线，覆盖：

- JMM 到底是什么，以及它和 JVM 内存结构不是一回事
- 并发里最核心的三个问题：原子性、可见性、有序性
- `happens-before` 规则到底在约束什么
- `volatile` / `synchronized` / `final` 在 JMM 下的细粒度语义对比
- 几个经典面试题：可见性死循环、指令重排、双重检查锁、safe publication
- 从底层看 `volatile`：CPU cache、MESI、内存屏障、release/acquire
- 从源码视角看 `CAS`、`ConcurrentHashMap`、`AQS/ReentrantLock` 怎么利用 JMM 规则

如果你想按专题继续往下看，可以配合下面这些文档：

- [`jmm-introduction.md`](./jmm-introduction.md)：入门版说明，先解释 JMM 是什么，以及 CPU 缓存、JIT 优化、重排序、多核架构为什么会造成可见性和有序性问题
- [`thread-creation.md`](./thread-creation.md)：聚焦 `new Thread()` / `start()` 时 JVM 规范、HotSpot 实现和 JMM 分别在关注什么
- [`volatile-jmm.md`](./volatile-jmm.md)：只聚焦 `volatile` 的可见性与发布语义
- [`jcstress-ordering-partial.md`](./jcstress-ordering-partial.md)：用 JCStress 拆开“volatile 放在不同位置为什么不是等价写法”
- [`non-volatile-stop-flag-and-xint.md`](./non-volatile-stop-flag-and-xint.md)：单独解释“为什么普通 stop flag 可能停不下来，以及为什么 `-Xint` 常常又能退出”
- [`cas-notes.md`](./cas-notes.md)：只聚焦 `CAS` 的实现原理、内存语义、ABA 与常见用法
- [`synchronized-notes.md`](./synchronized-notes.md)：只聚焦 `synchronized`、monitor、字节码、对象头
- [`wait-notify.md`](./wait-notify.md)：只聚焦 `wait/notify/notifyAll` 和条件等待
- [`lock-support.md`](./lock-support.md)：只聚焦 `LockSupport`、permit、`park/unpark` 与 AQS
- [`references/jmm/jsr-133-faq.md`](./references/jmm/jsr-133-faq.md)：JSR-133 FAQ 本地整理版
- [`references/jmm/double-checked-locking-is-broken.md`](./references/jmm/double-checked-locking-is-broken.md)：DCL 历史资料

---

## 1. JMM 到底是什么

JMM，`Java Memory Model`，中文一般叫“Java 内存模型”。

它讨论的不是“堆、栈、方法区怎么分配”，而是：

- 多个线程同时读写共享变量时，什么结果是合法的
- 一个线程的写入什么时候对另一个线程可见
- 哪些操作允许重排，哪些顺序必须被保留

先区分两个容易混淆的概念：

- **JVM 内存结构**：堆、虚拟机栈、程序计数器、方法区这些，回答“运行时数据区怎么分”
- **JMM**：线程通过共享内存交互时的语义规则，回答“线程之间怎样彼此看见、怎样排序、怎样同步”

也就是说：

- `jvm/docs/jvm-memory.md` 更偏“JVM 运行时区域”
- 本文更偏“Java 并发读写共享变量的规则”

---

## 2. 为什么需要 JMM

如果没有 JMM，并发程序的行为就会直接暴露在这些底层机制之上：

- 编译器优化
- JIT 优化
- CPU 指令重排
- CPU cache / store buffer
- 多核之间的缓存一致性传播延迟

这样会带来三个很典型的问题。

### 2.1 原子性

不是所有操作都是不可分割的。

例如：

```java
count++;
```

它通常会拆成：

1. 读 `count`
2. 计算 `count + 1`
3. 写回 `count`

多线程同时执行时就可能丢更新。

### 2.2 可见性

线程 A 写了一个共享变量，线程 B 不一定马上能看到。

例如：

```java
class Demo {
    boolean ready = false;

    void writer() {
        ready = true;
    }

    void reader() {
        while (!ready) {
        }
        System.out.println("run");
    }
}
```

如果没有同步手段，`reader()` 线程可能一直看不到 `ready = true`。

### 2.3 有序性

代码里前后写着的顺序，不一定就是别的线程观察到的顺序。

```java
int data = 42;
ready = true;
```

如果缺少同步，另一个线程有可能先看到 `ready = true`，再看到旧的 `data`。

### 2.4 底层机制具体怎么制造这些问题

JMM 要屏蔽底层差异，不是因为 CPU、编译器或 JIT “做错了”，而是因为它们都会在不破坏单线程结果的前提下优化程序。

**CPU cache / store buffer 会影响可见性。** 多核 CPU 通常不会让每次读写都直接访问主内存。一个核心上的写入可能先进入本核心的 cache 或 store buffer，另一个核心仍然读到自己的旧视图。MESI 这类缓存一致性协议能让同一个地址的多个缓存副本最终趋于一致，但它不等于“一个 Java 线程写完后，所有线程立刻按程序语义看到这个写入”。

**编译器和 JIT 优化会影响可见性。** 如果一个变量没有 `volatile`、锁或其他同步约束，优化器可以认为当前线程里的循环不需要反复重新读取这个变量。例如：

```java
while (!stop) {
}
```

如果 `stop` 是普通字段，并且当前线程没有修改它，JIT 可能把它当成一个稳定值来优化。此时即使另一个线程写入 `stop = true`，当前线程也没有可靠保证能看到这个修改。问题不在于 JVM 忽略了别的线程，而在于代码没有给 JVM 一个跨线程同步边界。

**指令重排序和多核观察顺序会影响有序性。** `as-if-serial` 只保证单线程看起来像按源码顺序执行，不保证其他线程也按同样顺序观察到所有变量的变化。对下面这种发布模式：

```java
data = 42;
ready = true;
```

如果没有同步约束，编译器、CPU 或缓存传播过程都可能让读线程先看到 `ready == true`，但仍然看到旧的 `data`。多核环境会放大这个问题，因为读写线程可能真的同时运行在不同核心上，各自有寄存器、缓存、store buffer 和执行流水线。

所以 JMM 的职责不是让所有共享变量自动线程安全，而是规定：当程序使用了 `volatile`、`synchronized`、`Thread.start()`、`Thread.join()`、安全发布等同步动作时，这些动作必须建立明确的可见性和有序性边界。

---

## 3. JMM 的抽象模型

JMM 常用一个抽象模型来解释线程如何看待共享变量：

- **主内存（Main Memory）**：共享变量的“最终共享位置”
- **工作内存（Working Memory）**：每个线程自己的本地视图/缓存副本

这个“工作内存”是规范层面的抽象，不表示 JVM 里真的有一个叫这个名字的物理区域。它更像是在统一描述：

- 寄存器
- CPU cache
- 编译器/JIT 保留下来的局部值

JMM 的核心思想是：

- 线程不会直接“看见”别的线程工作内存里的值
- 线程之间的可见性，必须通过明确的同步动作建立

---

## 4. `happens-before`：JMM 的核心判断标准

理解 JMM，最重要的关键词就是 `happens-before`。

如果 A `happens-before` B，那么 JMM 保证：

- A 的结果对 B 可见
- A 在内存语义上先于 B

注意，它不是“物理时间上一定更早”，而是“JMM 保证你可以按这个顺序推理”。

常见规则如下。

### 4.1 程序次序规则

同一个线程里，按照代码顺序，前面的操作先行发生于后面的操作。

注意这只是语义顺序，不等于底层完全禁止重排。只要不破坏单线程结果，编译器和 CPU 仍然可以优化。

### 4.2 监视器锁规则

对同一把锁：

- 一个线程的 `unlock`
- happens-before
- 另一个线程后续对同一把锁的 `lock`

这就是 `synchronized` 能提供可见性的根本。

### 4.3 `volatile` 变量规则

对同一个 `volatile` 变量：

- 一个线程的写
- happens-before
- 另一个线程后续读到这个值

这就是 `volatile` 能做“发布”的根本。

### 4.4 传递性

如果：

- A happens-before B
- B happens-before C

那么：

- A happens-before C

### 4.5 线程启动规则

线程 A 在调用 `thread.start()` 之前的操作，happens-before 新线程中的任何操作。

### 4.6 线程终止规则

线程中的所有操作，happens-before 其他线程成功从 `join()` 返回，或者确认该线程已经终止。

### 4.7 中断规则

对某个线程调用 `interrupt()`，happens-before 该线程检测到中断。

---

## 5. `volatile` / `synchronized` / `final` 的细粒度语义对比

先看一张总表：

| 机制 | 主要解决的问题 | 原子性 | 可见性 | 有序性 | 典型用途 |
|---|---|---|---|---|---|
| `volatile` | 线程间状态传播 | 只保证单次读写，不保证复合操作 | 有 | 有，接近 release/acquire | 标志位、发布信号、替换整个引用 |
| `synchronized` | 共享可变状态的互斥访问 | 有，整个临界区受保护 | 有 | 有 | 计数、复合更新、跨多个变量维护一致性 |
| `final` | 构造结束后的初始化安全 | 不解决运行期复合并发 | 对“构造完成时的初始值”有特殊保证 | 禁止部分构造期重排 | 不可变对象、配置快照 |

### 5.1 `volatile`

`volatile` 主要提供两种语义：

- **可见性**
- **有序性**

它最经典的发布模式是：

```java
class Example {
    int data = 0;
    volatile boolean ready = false;

    void writer() {
        data = 42;
        ready = true;
    }

    void reader() {
        if (ready) {
            System.out.println(data);
        }
    }
}
```

如果读线程看到了 `ready == true`，那它也必须看到 `data == 42`。

这背后的直觉是：

- `volatile write` 很像 `release`
- `volatile read` 很像 `acquire`

也就是：

- 在 `volatile` 写之前的普通写，不能被重排到它之后
- 在 `volatile` 读之后的普通读，不能被重排到它之前

但它**不保证复合操作原子性**。例如：

```java
volatile int count = 0;

void inc() {
    count++;
}
```

这仍然不安全，因为 `count++` 不是一个原子动作。

### 5.2 `synchronized`

`synchronized` 同时提供：

- 互斥
- 可见性
- 有序性

它对应的 JMM 规则是：

- 对同一把锁的 `unlock` happens-before 后续对这把锁的 `lock`

这意味着：

- 线程退出同步块时，对共享变量的修改要对后续拿到同一把锁的线程可见
- 线程进入同步块时，要重新建立对共享状态的有效视图

所以 `synchronized` 不只是“避免两个线程同时进来”，它还承担了“发布修改结果”的职责。

### 5.3 `final`

`final` 最容易被低估。

它不负责运行期线程协调，而是提供一种“构造完成后的初始化安全”：

```java
class Holder {
    final int x;

    Holder() {
        x = 42;
    }
}
```

如果对象被正确构造，别的线程拿到这个对象后，看到 `x` 时应当是正确初始化后的值，而不是默认值。

但这里有两个前提：

- 这是针对“构造完成时的初始值”
- 构造过程中不能让 `this` 提前逃逸

典型反例：

```java
class Bad {
    final int x;

    Bad() {
        Global.ref = this; // this 逃逸
        x = 1;
    }
}
```

这会破坏 `final` 的初始化安全语义。

---

## 6. 用几道经典题把 JMM 串起来

### 6.1 可见性死循环

```java
class Worker {
    boolean running = true;

    void stop() {
        running = false;
    }

    void run() {
        while (running) {
        }
    }
}
```

为什么工作线程可能停不下来？

- 没有任何同步动作建立 happens-before
- `running` 可能一直被缓存为旧值
- JIT 甚至可能把循环优化成反复看一个稳定值

修复方式通常是：

```java
volatile boolean running = true;
```

或使用锁/中断。

### 6.2 指令重排与发布失效

```java
class ReorderDemo {
    int data = 0;
    boolean ready = false;

    void writer() {
        data = 42;
        ready = true;
    }

    void reader() {
        if (ready) {
            System.out.println(data);
        }
    }
}
```

如果没有同步：

- 读线程可能先看到 `ready == true`
- 但仍然看到旧的 `data == 0`

修复的典型方式是让 `ready` 成为 `volatile`，把它变成发布信号。

### 6.3 双重检查锁为什么必须配 `volatile`

```java
class Singleton {
    private static volatile Singleton instance;

    private Singleton() {}

    static Singleton getInstance() {
        if (instance == null) {
            synchronized (Singleton.class) {
                if (instance == null) {
                    instance = new Singleton();
                }
            }
        }
        return instance;
    }
}
```

`new Singleton()` 可以粗略理解成：

1. 分配内存
2. 初始化对象
3. 把引用赋给 `instance`

如果 2 和 3 被重排，另一个线程就可能看到一个“非空但未完全初始化”的对象。

`volatile` 在这里的作用是：

- 禁止这种关键重排
- 保证初始化结果对其他线程可见

### 6.4 什么是 safe publication

“对象被创建出来”和“对象被安全发布给别的线程”不是一回事。

这段代码不一定安全：

```java
class Holder {
    int n;

    Holder() {
        n = 42;
    }
}

class UnsafePublish {
    Holder holder;

    void init() {
        holder = new Holder();
    }
}
```

如果 `holder` 只是普通字段，别的线程看到 `holder != null`，不等于一定看到 `n == 42`。

常见的安全发布方式包括：

- 在静态初始化中创建
- 用 `volatile` 引用发布
- 用 `synchronized` / `Lock` 保护发布与读取
- 放入线程安全容器
- 使用不可变对象并正确发布

---

## 7. 从底层看 `volatile`：CPU cache、MESI、内存屏障、release/acquire

### 7.1 只有“缓存一致性”还不够

真实硬件世界里，多核 CPU 会涉及：

- 寄存器
- L1/L2/L3 cache
- store buffer
- 编译器和 CPU 的重排

MESI 这类缓存一致性协议，解决的是：

- 同一个 cache line 的副本如何最终收敛一致

但它不直接保证：

- 线程 B 什么时候一定看到线程 A 的写
- 不同变量之间的观察顺序如何被约束

所以：

- **cache coherence** 解决“同一地址最终一致”
- **JMM + 屏障** 解决“程序级同步语义”

### 7.2 内存屏障在做什么

内存屏障（memory barrier / fence）的作用，不是“把缓存全清空”，而是：

- 限制读写重排
- 约束编译器/JIT/CPU 不能跨过某个点乱移
- 在必要时让前面的写对其他核心足够可见

常见抽象包括：

- `LoadLoad`
- `LoadStore`
- `StoreStore`
- `StoreLoad`

其中最强、成本也通常最高的是 `StoreLoad`。

### 7.3 为什么说 `volatile` 像 release/acquire

工程上可以把：

- `volatile write` 看成 `release`
- `volatile read` 看成 `acquire`

对发布模式来说：

```java
data = 42;   // 普通写
ready = true; // volatile 写
```

当另一个线程：

```java
if (ready) { // volatile 读
    System.out.println(data);
}
```

读到了 `ready == true`，就应该也看到 `data == 42`。

这就是 `volatile` 最实用的理解方式：

- 它不是互斥工具
- 它是“带同步语义的状态传播/发布工具”

### 7.4 为什么 `volatile` 不能解决 `count++`

因为 `volatile` 只保证：

- 读写具有可见性
- 读写前后的顺序受约束

它不保证“读-改-写”整个复合过程是原子的。

所以：

```java
volatile int count = 0;
count++;
```

仍然可能丢更新。

### 7.5 x86 与 ARM 的差异

从 Java 语义上看，`volatile` 的规则是统一的；但 JVM 在不同硬件上的实现方式并不一样。

- 在 x86/x64 这类相对强内存模型上，硬件本身已经保证了不少顺序
- 在 ARM/AArch64 这类更弱内存模型上，JVM 往往需要插入更明确的 acquire/release 屏障

也就是说：

- JMM 规定“必须保证什么”
- HotSpot 负责把它翻译成具体平台上的指令与屏障

---

## 8. 从源码角度看 `CAS`、`ConcurrentHashMap`、`AQS`

### 8.1 CAS：最基础的原子更新原语

CAS，`Compare-And-Set` / `Compare-And-Swap`，语义是：

- 如果当前值等于期望值，就原子地更新为新值
- 否则失败

伪代码：

```java
boolean compareAndSet(V expected, V update) {
    if (value == expected) {
        value = update;
        return true;
    }
    return false;
}
```

关键不在 `if`，而在：

- “比较 + 写入”必须由 JVM/CPU 保证成一个不可分割的原子动作

从 JMM 视角看，CAS 常常同时承担两件事：

- 原子更新
- 类似 `volatile` 的内存可见性语义

所以原子类里常见这种写法：

```java
for (;;) {
    int cur = value;
    int next = cur + 1;
    if (compareAndSet(cur, next)) {
        return next;
    }
}
```

如果你想把 `CAS` 再单独拆开看，包括它在 Java API、HotSpot intrinsic、CPU 原子指令和 JMM 内存语义这几层是怎么串起来的，可以继续看 [`cas-notes.md`](./cas-notes.md)。

### 8.2 `ConcurrentHashMap`：`final + volatile + CAS + synchronized`

JDK 8 之后的 `ConcurrentHashMap` 可以概括成一句话：

- 读尽量无锁
- 空桶插入用 CAS
- 桶内复杂更新用 `synchronized`
- 状态协调靠 `volatile`

几个很典型的字段角色是：

- `table`：`volatile`
- `sizeCtl`：`volatile`
- `Node.hash`、`Node.key`：`final`
- `Node.val`、`Node.next`：`volatile`

这种分工非常典型：

- 不变字段用 `final`，保证节点构造后的初始化安全
- 可能被更新或遍历观察到的字段用 `volatile`
- 空桶抢占、状态竞争用 CAS
- 链表/树里的复杂修改用桶级别 `synchronized`

为什么 `get()` 基本可以无锁？

- 桶头通过 `tabAt` 之类的 volatile 风格读取获得
- 节点的 `hash/key` 是 `final`
- 节点的 `val/next` 是 `volatile`
- 所以读线程可以看到一个结构安全、语义合法的视图

为什么 `put()` 不直接全局加锁？

- 这样并发度太差
- `ConcurrentHashMap` 的策略是只锁冲突桶，而不是锁整个 map

扩容时还会用到：

- `sizeCtl`
- `nextTable`
- `ForwardingNode`

这些状态字段同样依赖 `volatile/CAS` 来做线程间协调和发布。

### 8.3 `AQS/ReentrantLock`：`volatile state + CAS + 队列 + park/unpark`

`AQS` 的核心字段非常值得记：

- `volatile int state`
- `volatile Node head`
- `volatile Node tail`

获取锁的大致思路是：

1. 先尝试 CAS 修改 `state`
2. 成功就直接拿到锁
3. 失败就进入等待队列
4. 在队列里自旋检查是否轮到自己
5. 条件不满足则 `park()`

释放锁时：

1. 更新 `state`
2. 如果锁真的释放了，就唤醒后继节点

这里要特别注意：

- `park/unpark` 负责的是线程挂起与唤醒
- 真正承担同步语义的是 `state/head/tail/waitStatus` 这些字段上的 `volatile/CAS` 协议

也就是说，AQS 本质上是在用库代码自己造出一套 acquire/release 语义。

如果你想把 `LockSupport` 本身再单独拆开看，包括 permit 为什么不会累加、`unpark` 为什么可以先于 `park`、它和 `wait/notify` / `Condition` 的差别，可以继续看 [`lock-support.md`](./lock-support.md)。

从直觉上可以这样理解：

- `unlock` 很像 `release`
- `lock` 成功返回很像 `acquire`

这就是 `Lock` 也能像 `synchronized` 一样建立 happens-before 的原因。

---

## 9. 一套实用的并发分析方法

遇到并发代码时，先别急着猜“应该没问题”。按下面这几步看：

1. 这里有没有共享变量？
2. 是否至少有一个线程在写？
3. 两个线程之间有没有明确的 happens-before？
4. 如果有，这个 happens-before 是谁建立的？
5. 建立方式是 `volatile`、锁、线程启动/终止、原子类，还是安全发布？

如果你回答不出第 4 步，代码大概率已经站在风险区了。

---

## 10. 开发里怎么选工具

可以先按这个优先级判断：

- **对象构造好后不再变化**：优先考虑不可变对象，结合 `final`
- **只是传播一个状态、开关或版本号**：优先考虑 `volatile`
- **需要维护共享可变状态的一致性**：优先考虑 `synchronized` 或 `Lock`
- **单变量原子更新**：优先考虑原子类和 CAS
- **更高层并发控制**：优先考虑 `ConcurrentHashMap`、阻塞队列、线程池、`CompletableFuture` 等现成并发工具

最常见的误区有这些：

- 以为 `volatile` 能保证“线程安全”
- 以为代码顺序就是别的线程能看到的顺序
- 以为“一个线程写了，另一个线程自然能看到”
- 以为加了某个 `synchronized` 就自动建立了所有共享状态的可见性
- 以为 `final` 就意味着整个对象天然线程安全

---

## 11. 一句话总结

如果把 JMM 压缩成一句话，可以记成：

- **JMM 规定了 Java 并发里共享变量的可见性、有序性边界，以及哪些同步动作可以建立可靠的 happens-before 关系。**

如果再压缩一点：

- **JMM = 可见性 + 有序性 + 原子性边界 + happens-before 规则。**

---

## 12. 建议阅读顺序

如果你是第一次系统学 JMM，建议按这个顺序读：

1. [`jmm-introduction.md`](./jmm-introduction.md)，先用入门例子建立 JMM 的问题背景
2. 本文，把 JMM 的主线完整建立起来
3. [`thread-creation.md`](./thread-creation.md)，把线程启动/终止规则和 JVM 线程上下文建立过程连起来
4. [`volatile-jmm.md`](./volatile-jmm.md)，把发布/可见性/反例吃透
5. [`jcstress-ordering-partial.md`](./jcstress-ordering-partial.md)，把“同样用了 volatile，为什么 Case2 和 Case3 结论相反”这件事吃透
6. [`cas-notes.md`](./cas-notes.md)，把原子类、CAS 自旋、HotSpot/CPU 实现链路补齐
7. [`synchronized-notes.md`](./synchronized-notes.md)，把 monitor、字节码、锁语义补齐
8. [`wait-notify.md`](./wait-notify.md)，把条件等待和 monitor 协作补齐
9. [`lock-support.md`](./lock-support.md)，把 `park/unpark`、permit 和 AQS 底层阻塞原语补齐
10. [`references/jmm/jsr-133-faq.md`](./references/jmm/jsr-133-faq.md)，再回到 JSR-133 FAQ 看官方口径

这样读下来，概念、代码、规范、源码视角会连成一条线。
