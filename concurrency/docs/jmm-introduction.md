# Java Memory Model 入门：为什么需要 JMM

本文是 JMM 的入门说明，重点回答两个问题：

- `Java Memory Model` 到底是什么？
- 为什么 CPU 缓存、编译器优化、指令重排序和多核架构会让多线程程序出现可见性、有序性问题？

如果已经理解这些基础概念，可以继续读 [`jmm-notes.md`](./jmm-notes.md)，那里按更完整的主线整理了 `volatile`、`synchronized`、`final`、safe publication、DCL、CAS、AQS 等内容。

---

## 1. JMM 是什么

JMM，`Java Memory Model`，中文一般叫“Java 内存模型”。

它不是在讲 JVM 运行时内存区域，比如堆、虚拟机栈、方法区、元空间，也不是在讲对象具体分配到哪里。JMM 讨论的是：

> 在多线程程序中，一个线程对共享变量的修改，什么时候、以什么顺序，对其他线程可见。

所以可以先把 JMM 理解成一套并发读写共享变量的语义规则。它回答这些问题：

- 一个线程写了共享变量，另一个线程什么时候能看到？
- 代码写在前面的操作，是否一定先被其他线程观察到？
- 哪些操作是不可拆分的，哪些操作会被多个线程交错执行？
- 哪些同步动作可以建立可靠的跨线程可见性和顺序关系？

最容易混淆的是 JMM 和 JVM 内存结构：

| 概念 | 关注点 | 典型问题 |
|---|---|---|
| JVM 内存结构 | 运行时数据区如何划分 | 堆、栈、方法区、程序计数器、元空间 |
| JMM | 线程之间如何通过共享变量交互 | 可见性、有序性、原子性、happens-before |

---

## 2. 为什么需要 JMM

现代计算机为了性能，会在很多层面做优化：

- CPU 有多级缓存和 store buffer。
- 编译器、JIT 会做循环优化、公共子表达式消除、寄存器缓存等优化。
- CPU 和编译器都可能在不破坏单线程结果的前提下重排序指令。
- 多核 CPU 上，不同线程可能真的同时在不同核心上运行。
- x86、ARM、RISC-V 等硬件平台的内存一致性模型并不完全一样。

这些优化本身是合理的。没有它们，程序会慢很多。

问题在于：单线程里“看起来没问题”的优化，放到多线程共享变量场景下，就可能让另一个线程看到旧值，或者看到和源码顺序不一致的结果。

JMM 的作用就是在性能和可推理性之间建立边界：

```text
既允许 JVM、JIT、CPU 做优化，
又给 Java 程序员提供统一的并发语义。
```

如果 Java 没有自己的内存模型，同一段多线程代码在不同 CPU、不同 JVM 优化策略下就可能表现不一致，程序员也很难判断“这个变量什么时候一定能被另一个线程看到”。

---

## 3. JMM 主要解决的三个问题

### 3.1 可见性

可见性是指：

> 一个线程对共享变量的修改，其他线程是否能及时看到。

例如：

```java
class StopDemo {
    static boolean running = true;

    public static void main(String[] args) {
        new Thread(() -> {
            while (running) {
            }
            System.out.println("退出");
        }).start();

        running = false;
    }
}
```

直觉上，主线程把 `running` 改成 `false` 后，工作线程应该退出循环。

但如果 `running` 是普通字段，并且没有任何同步动作，工作线程不一定能可靠看到主线程的写入。它可能一直读到旧值 `true`。

常见修复方式是：

```java
static volatile boolean running = true;
```

`volatile` 的含义不是“让循环变慢一点”，而是告诉 JVM：这个变量参与跨线程通信，对它的读写必须遵守 `volatile` 的可见性和有序性规则。

### 3.2 有序性

有序性是指：

> 一个线程里的源码顺序，是否一定也是其他线程观察到的顺序。

例如：

```java
int data = 0;
boolean ready = false;
```

写线程：

```java
data = 42;
ready = true;
```

读线程：

```java
if (ready) {
    System.out.println(data);
}
```

直觉上，读线程既然看到了 `ready == true`，就应该看到 `data == 42`。

但如果没有同步约束，读线程有可能看到：

```text
ready == true
data == 0
```

原因可能是编译器或 CPU 重排序，也可能是两个变量对其他 CPU 核心的可见顺序不一致。

如果把 `ready` 声明为 `volatile`：

```java
volatile boolean ready = false;
```

那么写线程中的：

```java
data = 42;
ready = true;
```

会形成发布语义。读线程一旦读到 `ready == true`，就能可靠看到写 `ready` 之前的普通写入结果，也就是 `data == 42`。

### 3.3 原子性

原子性是指：

> 一个操作要么完整执行，要么完全不执行，中间不能被其他线程打断。

例如：

```java
count++;
```

这一行不是原子操作。它大致可以拆成三步：

```text
1. 读取 count
2. 计算 count + 1
3. 写回 count
```

两个线程同时执行 `count++` 时，可能都读到旧值 `0`，然后都写回 `1`。最终结果应该是 `2`，实际却可能是 `1`。

解决这类复合更新，通常要用：

- `synchronized` 或 `Lock` 保护整个临界区
- `AtomicInteger`、`AtomicLong` 等原子类
- 更高层的并发容器或并发工具

`volatile int count` 仍然不能让 `count++` 变成原子操作。

---

## 4. 底层机制为什么会导致可见性和有序性问题

### 4.1 CPU 缓存和 store buffer

CPU 比内存快很多，所以现代 CPU 不会每次读写都直接访问主内存，而是会使用寄存器、多级缓存和 store buffer。

可以简化成：

```text
CPU Core 1 -> L1/L2 Cache -> Store Buffer
CPU Core 2 -> L1/L2 Cache -> Store Buffer
主内存 RAM
```

假设共享变量一开始是：

```java
static boolean flag = false;
```

可能出现这样的视图：

```text
主内存：flag = false
CPU 1 缓存：flag = false
CPU 2 缓存：flag = false
```

线程 A 在 CPU 1 上执行：

```java
flag = true;
```

这个写入可能先进入 CPU 1 的缓存或 store buffer：

```text
CPU 1 视图：flag = true
CPU 2 视图：flag = false
```

线程 B 如果在 CPU 2 上运行，它就可能仍然读到旧值。

MESI 这类缓存一致性协议会处理同一地址的缓存副本一致性，但它不等于 Java 层面的“线程 A 写完后，线程 B 立刻能按程序语义看到”。JMM 需要定义的是程序级同步语义，而不是只依赖某一种 CPU 的缓存行为。

### 4.2 编译器和 JIT 优化

编译器和 JIT 会根据当前线程的代码做优化。

例如：

```java
while (!stop) {
}
```

如果 `stop` 是普通字段，并且当前线程里没有任何代码修改它，JIT 可能认为这个值在循环中不需要反复读取，于是优化成类似：

```java
if (!stop) {
    while (true) {
    }
}
```

此时另一个线程即使执行：

```java
stop = true;
```

当前线程也没有可靠保证能看到。

这不是 JVM 随意破坏语义，而是因为程序没有建立跨线程同步边界。没有 `volatile`、锁或其他同步动作，JMM 不要求普通读写具备可靠的跨线程可见性。

### 4.3 指令重排序

编译器和 CPU 可以在不改变单线程结果的前提下调整执行顺序。

例如：

```java
a = 1;
b = 2;
```

如果单线程观察结果不变，实际执行顺序可能变成：

```java
b = 2;
a = 1;
```

这符合 `as-if-serial` 语义：

> 不管怎么优化，单线程看起来都像按源码顺序执行。

但是多线程程序的问题在于：其他线程会观察共享变量的变化。一个线程看起来没有问题的重排序，另一个线程可能看到完全不同的状态组合。

发布模式就是典型例子：

```java
data = 42;
ready = true;
```

如果缺少同步，读线程可能先看到 `ready == true`，但仍然看到旧的 `data == 0`。

### 4.4 多核架构会放大问题

在单核、单线程、完全串行的世界里，很多问题不会暴露得那么明显。

多核 CPU 下，线程可以真的同时运行：

```text
线程 A 在 CPU Core 1 上运行
线程 B 在 CPU Core 2 上运行
```

每个核心都有自己的寄存器、缓存、store buffer 和执行流水线。于是线程 A 的写入，线程 B 不一定马上看到；线程 A 按顺序写入的两个变量，线程 B 也不一定按同样顺序观察到。

可以把有序性问题简化成：

```text
时间 1：线程 A 写 data = 42
时间 2：线程 A 写 ready = true
时间 3：线程 B 看到 ready = true
时间 4：线程 B 仍然看到 data = 0
```

这就是为什么 JMM 要规定跨线程同步动作，而不是让程序员直接依赖“机器应该会尽快同步缓存”。

---

## 5. JMM 的抽象模型

JMM 常用两个概念来解释线程如何看待共享变量：

- **主内存（Main Memory）**：共享变量的公共位置。
- **工作内存（Working Memory）**：每个线程自己的本地视图。

可以简化理解为：

```text
线程 A 工作内存        主内存        线程 B 工作内存
     x 副本   <---->    x    <---->     x 副本
```

这里的“工作内存”是规范层面的抽象，不是 JVM 里真的有一个叫工作内存的物理区域。它可以对应寄存器、CPU cache、JIT 保留下来的局部值等实现细节。

这个抽象要表达的是：

- 线程不会直接看见其他线程的本地视图。
- 普通共享变量读写没有可靠的跨线程可见性保证。
- 线程之间要建立可靠关系，必须依赖同步动作。

---

## 6. happens-before

`happens-before` 是 JMM 里最重要的推理规则。

如果操作 A `happens-before` 操作 B，那么 JMM 保证：

- A 的结果对 B 可见。
- A 在内存语义上排在 B 之前。

它不是单纯的物理时间先后，而是 Java 内存模型允许程序员进行推理的语义先后关系。

常见规则包括：

| 规则 | 含义 |
|---|---|
| 程序次序规则 | 同一个线程内，前面的操作 happens-before 后面的操作 |
| `volatile` 规则 | 对同一个 `volatile` 变量的写 happens-before 后续读到该写的读 |
| 锁规则 | 对同一把锁的 `unlock` happens-before 后续对这把锁的 `lock` |
| 线程启动规则 | `Thread.start()` 之前的操作 happens-before 新线程中的操作 |
| 线程终止规则 | 线程中的操作 happens-before 其他线程从 `join()` 成功返回 |
| 传递性 | 如果 A happens-before B，B happens-before C，那么 A happens-before C |

例如：

```java
int data = 0;
volatile boolean ready = false;

// 线程 A
data = 42;
ready = true;

// 线程 B
if (ready) {
    System.out.println(data);
}
```

这里的关键链路是：

```text
线程 A 写 data = 42
    happens-before
线程 A 写 volatile ready = true
    happens-before
线程 B 读 volatile ready == true
    happens-before
线程 B 读取 data
```

所以线程 B 如果读到了 `ready == true`，就能看到 `data == 42`。

---

## 7. 常见同步机制和 JMM 的关系

### 7.1 volatile

`volatile` 主要提供：

- 可见性
- 禁止特定重排序

适合传播状态、开关、版本号或发布信号：

```java
volatile boolean stop = false;
```

但它不保证复合操作的原子性：

```java
volatile int count = 0;

count++;
```

这仍然不是线程安全的。

工程上可以把 `volatile` 理解成一种轻量发布机制：

- `volatile write` 类似 release。
- `volatile read` 类似 acquire。

写 `volatile` 之前的普通写，不能被重排到它之后；读 `volatile` 之后的普通读，不能被重排到它之前。

### 7.2 synchronized

`synchronized` 同时提供：

- 互斥
- 可见性
- 有序性

JMM 对它的核心规则是：

> 对同一把锁的 `unlock` happens-before 后续对这把锁的 `lock`。

所以 `synchronized` 不只是“不让两个线程同时进入临界区”。它还保证：

- 释放锁之前的修改，对后续拿到同一把锁的线程可见。
- 获取锁之后，线程可以看到这把锁保护下共享状态的有效视图。

### 7.3 final

`final` 字段有初始化安全语义。

如果对象被正确构造，并且构造期间 `this` 没有逃逸，那么其他线程拿到这个对象后，应该能看到构造函数中写入的 `final` 字段值。

例如：

```java
class User {
    final String name;

    User(String name) {
        this.name = name;
    }
}
```

这也是不可变对象更适合并发共享的原因之一。

但 `final` 不等于整个对象天然线程安全。它主要保护构造完成时的初始化结果，不负责对象后续可变字段的并发协调。

### 7.4 Lock 和 Atomic 类

`Lock` 的加锁、解锁语义和 `synchronized` 类似，也能建立 happens-before。

`AtomicInteger`、`AtomicLong` 等原子类适合单变量原子更新。它们底层通常依赖 CAS，并带有对应的内存语义。

例如：

```java
AtomicInteger count = new AtomicInteger();

count.incrementAndGet();
```

---

## 8. 如果没有 JMM，会怎么样

如果没有 JMM，Java 多线程程序会非常难以推理：

1. 同一段代码在不同 CPU 上可能表现不一样。
2. 编译器和 JIT 不知道哪些优化会破坏多线程语义。
3. 程序员不知道什么时候共享变量修改对其他线程可见。
4. 指令重排序会让跨线程观察结果变得不可控。
5. Java “一次编写，到处运行”的一致性会受到影响。

JMM 并不会让所有错误同步的程序自动正确。它提供的是边界：

```text
没有同步，线程之间读写共享变量没有可靠保证。
有正确同步，Java 保证这些同步动作具有统一的可见性和有序性语义。
```

---

## 9. 一个简单类比

可以把多线程共享变量类比成公司协作：

```text
主内存 = 公司公共白板
CPU 缓存 / 工作内存 = 每个人自己的笔记本
线程 = 员工
```

员工 A 把任务状态写在自己的笔记本上：

```text
任务完成 = true
```

但他还没有按公司制度更新公共白板。员工 B 看自己的笔记本，仍然看到：

```text
任务完成 = false
```

这就是可见性问题。

再比如员工 A 原本应该按顺序写：

```text
1. 先写任务结果
2. 再写任务完成
```

但为了提高效率，他先写了“任务完成”，结果员工 B 先看到了完成标记，却还没看到任务结果。

这就是有序性问题。

JMM 就像公司制度：它规定什么情况下必须发布结果、什么情况下必须重新读取、什么情况下不能改变关键顺序。

---

## 10. 实用总结

JMM 可以压缩成一句话：

> JMM 规定了 Java 并发里共享变量的可见性、有序性和原子性边界，并定义哪些同步动作可以建立可靠的 happens-before 关系。

再简化一点：

```text
JMM 解决的是：线程之间如何安全地共享变量。
```

排查并发代码时，可以按这个顺序问：

1. 这里有没有共享变量？
2. 是否至少有一个线程会写？
3. 读写之间有没有明确的 happens-before？
4. 这个 happens-before 是由 `volatile`、锁、线程启动/终止、原子类还是安全发布建立的？
5. 如果没有同步关系，当前代码是否只是在依赖“应该能看到”的直觉？

工具选择可以先按这个方向判断：

| 场景 | 优先工具 |
|---|---|
| 只是传播一个状态、开关或版本号 | `volatile` |
| 需要维护多个共享变量的一致性 | `synchronized` / `Lock` |
| 单变量原子更新 | 原子类 |
| 对象构造后不再变化 | 不可变对象 + `final` |
| 更复杂的并发协调 | 并发容器、阻塞队列、线程池等高层工具 |

常见误区：

- 以为普通变量写了之后其他线程自然能看到。
- 以为源码顺序就是其他线程观察到的顺序。
- 以为 `volatile` 能保证 `count++` 这类复合操作线程安全。
- 以为 `synchronized` 只负责互斥，不负责可见性。
- 以为 `final` 等于整个对象天然线程安全。
