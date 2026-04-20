# Java `synchronized` 全面整理

这份文档把前面讨论过的内容整理成一条完整主线，覆盖：

- `synchronized` 三种常见写法的区别
- 它们在字节码层面的差异
- HotSpot 运行时如何基于 monitor 和对象头处理锁
- `wait/notify`、`Condition.await/signal` 的关系
- `wait` 为什么必须写在 `while` 里
- `sleep()` 和 `wait()` 的区别
- 面试可直接复述的总结版本

如果你想先从 JMM 的原子性 / 可见性 / 有序性、`happens-before`、`volatile` / `final` / `synchronized` 对比以及 `CAS/AQS/ConcurrentHashMap` 的整体关系开始，再落到 monitor 与字节码细节，可以先读 [jmm-notes.md](./jmm-notes.md)。

如果你想专门看 `wait/notify/notifyAll` 的时序图、`wait set`、`AbcPrinters` 示例和常见误区，可以再结合阅读 [wait-notify.md](./wait-notify.md)。

---

## 1. `synchronized` 的三种常见写法

Java 中最常见的三种写法：

### 1.1 同步代码块

```java
public void test() {
    synchronized (lock) {
        // 临界区
    }
}
```

特点：

- 锁对象是你显式指定的 `lock`
- 只锁住代码块中的内容
- 粒度最细，最灵活

---

### 1.2 普通同步方法

```java
public synchronized void test() {
    // 整个方法体都同步
}
```

特点：

- 锁对象是当前实例，也就是 `this`
- 同一个对象上的多个同步实例方法会互斥
- 不同实例之间互不影响

语义上等价于：

```java
public void test() {
    synchronized (this) {
        // 整个方法体
    }
}
```

---

### 1.3 静态同步方法

```java
public static synchronized void test() {
    // 整个静态方法体都同步
}
```

特点：

- 锁对象不是 `this`
- 锁的是当前类对应的 `Class` 对象，比如 `Demo.class`
- 属于类级别的锁，和具体实例无关

语义上等价于：

```java
public static void test() {
    synchronized (Demo.class) {
        // 整个方法体
    }
}
```

---

### 1.4 三者对比表

| 写法 | 锁对象 | 锁范围 | 典型等价形式 |
|---|---|---|---|
| `synchronized(obj) {}` | 指定对象 `obj` | 代码块 | 本身就是最灵活的形式 |
| `synchronized` 普通方法 | `this` | 整个实例方法 | `synchronized(this) {}` |
| `static synchronized` 静态方法 | `Class` 对象 | 整个静态方法 | `synchronized(Demo.class) {}` |

---

## 2. 字节码层面的区别

这一节只聚焦一个问题：

- `synchronized(lock) { ... }`
- `public synchronized void m() { ... }`
- `public static synchronized void m() { ... }`

它们在源码层面都表示“同步”，但在 class 文件里并不是用同一种方式编码的。

先给一个最小示例：

```java
public class SyncBytecodeExplain {
    private final Object lock = new Object();

    public void blockSync() {
        synchronized (lock) {
            work();
        }
    }

    public synchronized void instanceSync() {
        work();
    }

    public static synchronized void staticSync() {
        workStatic();
    }

    private void work() {
    }

    private static void workStatic() {
    }
}
```

用 `javac` 编译后，再执行：

```bash
javap -c -v -p SyncBytecodeExplain.class
```

下面只摘出和这 3 个方法直接相关的 `javap` 输出。
这些输出来自 OpenJDK 17；不同 JDK 版本在指令偏移量、`StackMapTable` 细节上可能略有差异，但本文要讲的主线不会变。

### 2.1 先看结论

- **同步代码块**：方法体里会出现显式的 `monitorenter` / `monitorexit`
- **普通同步方法**：方法体里通常没有 `monitorenter` / `monitorexit`，而是在方法标志位里带 `ACC_SYNCHRONIZED`
- **静态同步方法**：同样靠 `ACC_SYNCHRONIZED`，只是同时还有 `ACC_STATIC`，所以 JVM 知道它锁的是当前类的 `Class` 对象

也就是说，真正的分界线不是“实例方法 vs 静态方法”，而是：

- **同步代码块**：同步语义编码在 `Code` 属性里
- **同步方法**：同步语义编码在 `method_info.flags` 里

### 2.2 同步代码块：显式的 `monitorenter` / `monitorexit`

`blockSync()` 的 `javap -c -v -p` 输出如下：

```text
public void blockSync();
  descriptor: ()V
  flags: (0x0001) ACC_PUBLIC
  Code:
    stack=2, locals=3, args_size=1
       0: aload_0
       1: getfield      #7                  // Field lock:Ljava/lang/Object;
       4: dup
       5: astore_1
       6: monitorenter
       7: aload_0
       8: invokevirtual #13                 // Method work:()V
      11: aload_1
      12: monitorexit
      13: goto          21
      16: astore_2
      17: aload_1
      18: monitorexit
      19: aload_2
      20: athrow
      21: return
    Exception table:
       from    to  target type
           7    13    16   any
          16    19    16   any
    LineNumberTable:
      line 5: 0
      line 6: 7
      line 7: 11
      line 8: 21
    StackMapTable: number_of_entries = 2
      frame_type = 255 /* full_frame */
        offset_delta = 16
        locals = [ class SyncBytecodeExplain, class java/lang/Object ]
        stack = [ class java/lang/Throwable ]
      frame_type = 250 /* chop */
        offset_delta = 4
```

逐行看：

- `descriptor: ()V`
  - 方法签名是“无参数，返回 `void`”。

- `flags: (0x0001) ACC_PUBLIC`
  - 这里只有 `ACC_PUBLIC`，**没有 `ACC_SYNCHRONIZED`**。
  - 这说明同步不是靠方法标志位表达的，而是靠方法体里的字节码指令表达的。

- `Code:`
  - 从这一行开始，下面是方法体字节码。

- `stack=2, locals=3, args_size=1`
  - `stack=2`：操作数栈最大深度为 2。
  - `locals=3`：本地变量表会用到 3 个槽位。
  - `args_size=1`：这是实例方法，隐含参数 `this` 占 1 个槽位。

- `0: aload_0`
  - 把本地变量表 slot 0 的值压栈。
  - 对实例方法来说，slot 0 就是 `this`。

- `1: getfield #7 // Field lock:Ljava/lang/Object;`
  - 读取 `this.lock`，把锁对象压到操作数栈顶。

- `4: dup`
  - 复制一份栈顶的锁对象引用。
  - 这么做是因为后面的 `monitorenter` 会消耗掉一个对象引用，而编译器还需要保留同一个锁对象，用在正常路径和异常路径里的 `monitorexit`。

- `5: astore_1`
  - 把复制出来的那份锁对象保存到本地变量 slot 1。
  - 此后 slot 1 可以理解成“临时保存的 monitor 对象”。

- `6: monitorenter`
  - 这是同步代码块真正的“加锁”指令。
  - 它会弹出栈顶对象引用，并尝试获取该对象的 monitor。
  - 如果当前线程已经持有这把锁，则表现为重入，重入计数加 1。
  - 如果其他线程持有这把锁，则当前线程在这里阻塞。
  - 如果对象引用是 `null`，这里会抛 `NullPointerException`。

- `7: aload_0`
  - 把 `this` 再压栈，为调用 `work()` 做准备。

- `8: invokevirtual #13 // Method work:()V`
  - 调用实例方法 `work()`。
  - 到这一步时，线程已经持有 `lock` 对应的 monitor，因此 `work()` 的执行位于临界区内。

- `11: aload_1`
  - 正常路径下，把之前保存在 slot 1 里的锁对象重新压栈。

- `12: monitorexit`
  - 这是正常路径上的“解锁”指令。
  - 它会弹出栈顶对象引用，并尝试释放该对象 monitor。
  - 如果当前线程并不持有这把锁，这里会抛 `IllegalMonitorStateException`。
  - 如果当前线程持有这把锁，则重入计数减 1；减到 0 才算真正释放。

- `13: goto 21`
  - 正常解锁后，跳过下面的异常路径，直接去 `return`。

- `16: astore_2`
  - 这是异常路径的入口。
  - 如果 `7` 到 `12` 之间的代码抛了异常，JVM 会根据异常表跳到这里，并把异常对象保存到 slot 2。

- `17: aload_1`
  - 异常路径下，把同一个锁对象重新压栈。

- `18: monitorexit`
  - 异常路径也要执行一次解锁。
  - 这就是同步代码块在字节码层面为什么看起来像 `try/finally`：正常结束要释放锁，异常结束也要释放锁。

- `19: aload_2`
  - 把原始异常重新压栈。

- `20: athrow`
  - 重新抛出原始异常。
  - 这里不是“吞掉异常”，而是“先解锁，再把异常继续向外抛”。

- `21: return`
  - 正常返回。

再看异常表：

```text
Exception table:
   from    to  target type
       7    13    16   any
      16    19    16   any
```

它表达的是：

- `7 13 16 any`
  - 如果字节码偏移 `[7, 13)` 这段里出现任何异常，控制流都跳到 16。
  - 也就是：一旦线程已经进入临界区，但还没走完“正常解锁”这条路径，就要改走异常清理路径。

- `16 19 16 any`
  - 异常处理器自身这段也被 catch-all 包围。
  - 从结构上看，这更接近编译器生成的 `finally` 模板。
  - 在这里理解主线时，不要把重点放在“它能不能无限跳回自己”这种形式细节上，而应抓住它的语义：**无论怎样离开临界区，先执行一次解锁逻辑**。

最后看 `LineNumberTable` 和 `StackMapTable`：

- `LineNumberTable`
  - 用于把字节码偏移映射回源码行号，主要服务于调试和异常栈显示。

- `StackMapTable`
  - 用于字节码校验时做类型和控制流验证。
  - 它不是同步语义本身的一部分，只是 class 文件校验辅助信息。

这一节可以压成一句话：

- **同步代码块的 monitor 语义，是靠显式的 `monitorenter` / `monitorexit` 再加上异常表共同完成的。**

### 2.3 普通同步方法：`ACC_SYNCHRONIZED`

`instanceSync()` 的 `javap -c -v -p` 输出如下：

```text
public synchronized void instanceSync();
  descriptor: ()V
  flags: (0x0021) ACC_PUBLIC, ACC_SYNCHRONIZED
  Code:
    stack=1, locals=1, args_size=1
       0: aload_0
       1: invokevirtual #13                 // Method work:()V
       4: return
    LineNumberTable:
      line 11: 0
      line 12: 4
```

逐行看：

- `public synchronized void instanceSync();`
  - `javap` 已经把源码层的 `synchronized` 展示出来了。

- `descriptor: ()V`
  - 无参数，返回 `void`。

- `flags: (0x0021) ACC_PUBLIC, ACC_SYNCHRONIZED`
  - 关键在这里：方法标志位里有 `ACC_SYNCHRONIZED`。
  - 这说明这个方法的同步语义不是通过方法体里的显式监视器指令表达的，而是通过方法元数据告诉 JVM：
    - “这是一个同步方法”
    - “调用它时要自动做 monitor enter / exit 语义”

- `Code:`
  - 下面的方法体里已经看不到 `monitorenter` / `monitorexit`。

- `stack=1, locals=1, args_size=1`
  - `locals=1` 说明这里只需要保存 `this`。
  - 编译器不需要像同步代码块那样额外保存“锁对象副本”或“异常对象”，因为加锁解锁不是通过显式字节码完成的。

- `0: aload_0`
  - 把 `this` 压栈，为调用 `work()` 做准备。
  - 这条指令本身不是加锁。

- `1: invokevirtual #13 // Method work:()V`
  - 调用 `work()`。
  - 真正的锁获取发生在“执行这个方法的第一条字节码之前”，不是在这里。

- `4: return`
  - 正常返回。
  - 真正的解锁发生在“方法正常返回的边界上”，不是靠显式 `monitorexit` 指令完成的。

普通同步方法的真正执行流程更接近：

1. 调用方执行 `invokevirtual instanceSync`
2. JVM 解析到目标方法，发现它带 `ACC_SYNCHRONIZED`
3. 在执行方法第一条字节码前，JVM 先获取接收者对象的 monitor，也就是 `this`
4. 然后才开始执行 `0: aload_0`
5. 方法正常 `return` 时，JVM 自动释放 `this` 的 monitor
6. 方法如果异常退出，JVM 也会自动释放 `this` 的 monitor，再把异常继续向外传播

因此：

- **普通同步方法锁的是 `this`**
- **同步语义不在 `Code` 里，而在 `ACC_SYNCHRONIZED` 这个方法标志里**

### 2.4 静态同步方法：`ACC_STATIC + ACC_SYNCHRONIZED`

`staticSync()` 的 `javap -c -v -p` 输出如下：

```text
public static synchronized void staticSync();
  descriptor: ()V
  flags: (0x0029) ACC_PUBLIC, ACC_STATIC, ACC_SYNCHRONIZED
  Code:
    stack=0, locals=0, args_size=0
       0: invokestatic  #16                 // Method workStatic:()V
       3: return
    LineNumberTable:
      line 15: 0
      line 16: 3
```

逐行看：

- `public static synchronized void staticSync();`
  - 这是一个静态同步方法。

- `descriptor: ()V`
  - 无参数，返回 `void`。

- `flags: (0x0029) ACC_PUBLIC, ACC_STATIC, ACC_SYNCHRONIZED`
  - 这里同时有两个关键标志：
    - `ACC_STATIC`
    - `ACC_SYNCHRONIZED`
  - 这告诉 JVM：这是一个“静态同步方法”，因此它没有 `this`，同步时锁的也不是实例对象，而是**声明这个方法的类对象**，也就是 `当前类.class`。

- `Code:`
  - 方法体里同样没有显式的 `monitorenter` / `monitorexit`。

- `stack=0, locals=0, args_size=0`
  - 静态方法没有 `this`，也没有参数，因此本地变量表和参数大小都更小。

- `0: invokestatic #16 // Method workStatic:()V`
  - 调用静态方法 `workStatic()`。
  - 这条指令本身也不是加锁。

- `3: return`
  - 正常返回。

静态同步方法的运行时流程更接近：

1. 调用方执行 `invokestatic staticSync`
2. JVM 解析到目标方法，发现它同时带 `ACC_STATIC` 和 `ACC_SYNCHRONIZED`
3. 在执行第一条字节码前，JVM 获取声明类对应的 `Class` 对象的 monitor
4. 执行方法体
5. 方法正常返回或异常退出时，JVM 自动释放该 `Class` monitor

因此：

- **静态同步方法锁的是 `当前类.class`**
- **它和普通同步方法一样，不依赖显式 `monitorenter` / `monitorexit`**

### 2.5 字节码层面的本质区别

把这三种写法放到一起看：

- **同步代码块**
  - 编译器把同步语义翻译进方法体
  - 核心载体是 `monitorenter` / `monitorexit`
  - 为了保证异常退出也释放锁，编译器还会生成异常表，效果接近 `try/finally`

- **普通同步方法**
  - 编译器不给方法体插入显式监视器指令
  - 编译器只在方法标志位上打 `ACC_SYNCHRONIZED`
  - JVM 在方法调用边界上自动加锁/解锁，锁对象是 `this`

- **静态同步方法**
  - 也是靠 `ACC_SYNCHRONIZED`
  - 只是因为同时有 `ACC_STATIC`，所以 JVM 选择锁 `当前类.class`

可以压成下面这张对照表：

| 写法 | class 文件里的主要特征 | 锁对象 |
|---|---|---|
| `synchronized(lock) { ... }` | `Code` 中有 `monitorenter` / `monitorexit`，通常伴随异常表 | 你写在括号里的那个对象 |
| `public synchronized void m() { ... }` | 方法标志里有 `ACC_SYNCHRONIZED` | `this` |
| `public static synchronized void m() { ... }` | 方法标志里有 `ACC_STATIC` + `ACC_SYNCHRONIZED` | `当前类.class` |

还有一个很容易混淆的点：

```java
public synchronized void m() {
    work();
}
```

和：

```java
public void m() {
    synchronized (this) {
        work();
    }
}
```

它们在“锁对象”上都可能是 `this`，但字节码并不一样：

- 前者：靠 `ACC_SYNCHRONIZED`
- 后者：靠 `monitorenter` / `monitorexit`

所以它们不是“同一套字节码只是写法不同”，而是**运行时锁语义相近，但 class 文件编码方式不同**。


## 3. 从字节码到 JVM monitor

无论是：

- `monitorenter` / `monitorexit`
- 还是 `ACC_SYNCHRONIZED`

最终都会落到 JVM 的 monitor 机制上。

每个 Java 对象都可以关联 monitor。HotSpot 在处理锁时，会结合对象头中的 **Mark Word** 来决定当前锁处于什么状态，以及是否需要膨胀为 `ObjectMonitor`。

---

## 4. 对象头、Mark Word 与锁状态

### 4.1 Mark Word 是什么

Mark Word 是 HotSpot 对象头中的一部分，用来保存对象的运行时元数据。典型内容包括：

- 锁状态信息
- 哈希码相关信息
- GC 年龄
- 某些实现版本里的线程偏向信息

可以把它理解成：对象头里和“锁、hash、年龄”等运行时状态相关的那块区域。

---

### 4.2 经典 HotSpot 模型

很多资料讲的是 JDK 8 经典模型，常见说法包括：

- 偏向锁
- 轻量级锁
- 重量级锁

经典模型里，Mark Word 会参与锁状态切换：

- 未锁定：保存普通头信息
- 偏向锁：Mark Word 中带有偏向线程信息
- 轻量级锁：Mark Word 可能指向线程栈上的锁记录
- 重量级锁：Mark Word 可能指向 `ObjectMonitor`

这就是很多老资料里“Mark Word 指针切换”的来源。

#### 4.2.1 lock record（栈锁记录）是什么（以及为什么它不只是“标记”）

在经典 JDK 8 语境里，“轻量级锁（thin lock / stack lock）”会在**当前线程的栈帧里**放一个锁记录（常被称为 *lock record* / *monitor record* / *stack lock*；在 HotSpot 源码里对应 `BasicLock` / `BasicObjectLock` 这类结构）。

可以把它理解成一次 `monitorenter` 的“栈上凭证”，它至少要承担两件事：

1. **保存 displaced header（被挤出的对象头）**
   - 轻量级锁会把对象头的 **原始 Mark Word** 先拷贝到 lock record 里（常叫 displaced header）。
   - 解锁时需要把对象头恢复回去；如果不存这份备份，就没法正确恢复对象头。

2. **作为 owner token（所有者凭证）**
   - 轻量级锁加锁成功后，对象头会变成“**指向该 lock record 的指针 + 锁状态位**”。
   - 由于每个线程的栈地址空间互不重叠，“指针指向哪个线程的栈”就隐含了“哪条线程持有锁”，不需要额外在对象头里塞 thread id。

它不是用来“阻塞/唤醒线程”的结构：真正需要挂起/唤醒时，HotSpot 会把锁**膨胀**到 `ObjectMonitor`（重量级 monitor）上去处理。

#### 4.2.2 轻量级锁（thin lock）的加锁 / 重入 / 解锁主线（经典模型）

下面这条流程，就是你经常看到的那段描述背后的含义（仍以经典 JDK 8 的 thin lock 语境为主）：

**(1) 第一次进入：CAS 把对象头改成指向 lock record 的指针**

```text
T1 栈帧里创建 lock record
  - record.displaced = obj.markWord (原始 Mark Word)

CAS(obj.markWord, expected=record.displaced, new=ptr(record)|THIN_TAG)
  - 成功：T1 持有 thin lock
  - 失败：走慢路径（可能自旋，必要时膨胀）
```

**(2) 同线程重入：通过“对象头是否指向我栈上记录”识别**

- 如果对象头是 thin lock 且指向当前线程栈上的某条 lock record，那么这次进入属于**可重入**。
- 典型实现会再压入一条 lock record 作为“重入层”，其 displaced header 可能写入特殊值，表示这层不负责恢复对象头。

**(3) 退出：最外层负责恢复对象头**

- 退出重入层：直接弹出对应的 lock record。
- 退出最外层：CAS 尝试把对象头从 `ptr(record)|THIN_TAG` 改回 `record.displaced`（原始 Mark Word）。
  - CAS 成功：解锁完成
  - CAS 失败：说明期间发生了竞争/膨胀等，需要走慢路径（通常转交给 `ObjectMonitor`）

**(4) 竞争：自旋失败就膨胀**

- 线程 B 看到对象头指向“别的线程栈上的 lock record”时，B 不会去“读取/复用那条记录”来完成阻塞。
- 常见策略是先短自旋；自旋失败、或遇到 `wait()`/JNI monitor 等必须走 monitor 的场景，就会膨胀成 `ObjectMonitor`，由它来维护 owner、重入次数、队列与唤醒。

---

### 4.3 新版本 HotSpot 的变化

这里要补充版本背景：

- JDK 15：偏向锁默认关闭并被废弃，见 [JEP 374](https://openjdk.org/jeps/374)
- JDK 18：偏向锁相关参数被废弃为无效，见 [JDK-8301897](https://bugs.openjdk.org/browse/JDK-8301897)
- JDK 23：默认锁模式切到轻量级实现，见 [JDK-8327089](https://bugs.openjdk.org/browse/JDK-8327089)

#### 4.3.1 为什么要移除偏向锁（Biased Locking）

一句话概括偏向锁的目标：

- **优化“无竞争 + 总是同一线程进入同一把锁”的场景**：在对象头里记录“偏向于线程 T”，让同线程反复进入时尽量不再做 CAS。

但它后来逐步被默认关闭并退出主线，通常是因为：

1. **收益变小（命中率下降）**
   - 线程池/工作窃取/异步调度让“线程漂移”更常见，同一对象的锁更可能被多个线程轮流获取。
   - 命中率下降后，偏向锁的优势被稀释，反而更容易频繁触发撤销。

2. **撤销偏向的最坏代价高，容易劣化尾延迟**
   - 偏向撤销往往需要和目标线程做协调（例如检查对应的栈帧记录，确保状态可安全转换）。
   - 在某些竞争形态下会出现“偶发但很贵”的慢路径，影响 tail latency。

3. **实现复杂、维护成本高**
   - 对象头状态机与 JIT fast-path 分支变复杂。
   - 需要和 identity hash、膨胀到 `ObjectMonitor`、GC 标记等对象头复用状态做更多交互处理。

4. **替代优化更强**
   - CPU 原子指令更快，轻量级/fast path（CAS + 短自旋）的相对成本下降。
   - 逃逸分析 + 锁消除/锁粗化也让大量“看起来有锁”的代码运行时根本不需要进入 monitor。

新 HotSpot 的一个重要演进是：

- 不再依赖旧式“把线程栈锁记录地址塞进对象头”的方式
- 更倾向于只用 Mark Word 的低位锁标志表示状态
- 更复杂的 monitor 信息外移到独立结构或表中管理

所以如果看到资料在详细讲偏向锁和旧式轻量级锁，要知道它主要对应的是 JDK 8 一代的经典实现语境。

---

## 5. `ObjectMonitor`、可重入与运行时语义

monitor 至少要维护三件事：

- 当前锁的 owner 是谁
- 当前已经重入了多少层
- 等待线程放在哪些队列里

OpenJDK 当前 `ObjectMonitor` 中可以看到类似字段：

- `_owner`
- `_recursions`
- `_entry_list`
- `_wait_set`

---

### 5.1 `synchronized` 为什么是可重入的

因为 monitor 不只知道“锁是否被占用”，还知道：

- 当前持有者是谁
- 当前线程是否已经持有过这把锁
- 如果已经持有过，重入次数是多少

同一线程再次进入同一把锁时，不会阻塞，而是增加重入层数。

例如：

```java
public synchronized void a() {
    b();
}

public synchronized void b() {
}
```

这里不会死锁，因为进入 `a()` 的线程已经持有了 `this` 的 monitor，再进入 `b()` 只是重入。

---

## 6. `wait()`、`notify()`、`notifyAll()` 的本质

这一节只保留总览结论。更完整的时序图、线程视角、`wait set` 解释和仓库内 `AbcPrinters` 示例，见 [wait-notify.md](./wait-notify.md)。

### 6.1 `wait()` 做了什么

调用 `wait()` 时，线程会：

1. 确认自己已经持有 monitor
2. 释放 monitor
3. 进入该对象 monitor 的等待集合
4. 被 `notify` / `notifyAll` / 超时 / 中断 / 伪唤醒后恢复
5. 重新竞争 monitor
6. 重新获得 monitor 后才从 `wait()` 返回

所以 `wait()` 的本质不是“睡一会儿”，而是：

- 释放锁
- 进入条件等待
- 醒来后重新拿锁

---

### 6.2 为什么 `wait()` 必须在同步块中调用

因为如果当前线程没有持有 monitor，JVM 就无法安全完成：

- 安全释放 monitor
- 将线程挂到这把锁对应的等待集合中
- 之后重新竞争这把锁

因此：

```java
lock.wait();
```

如果没有放在：

```java
synchronized (lock) {
    lock.wait();
}
```

里，就会抛出 `IllegalMonitorStateException`。

---

### 6.3 `notify()` / `notifyAll()` 做了什么

- 把等待线程从等待集合中转移出来
- 让它们有资格去重新竞争 monitor

所以线程被通知后并不是立刻往下执行，而是必须先重新拿到 monitor，`wait()` 才会真正返回。

---

## 7. `WaitSet` 和 `EntryList`

可以先用一个最短区分：

- `WaitSet`：已经拿到过锁，但因为条件不满足而 `wait()` 的线程
- `EntryList`：想进入 monitor，或者被唤醒后正在重新竞争锁的线程

也就是说：

- `WaitSet` 等的是条件
- `EntryList` 等的是锁

如果你想看更展开的图示和“wait 之后为什么会先从等待通知变成等待拿锁”，见 [wait-notify.md](./wait-notify.md)。

---

## 8. `wait/notify` 和 `Condition.await/signal` 的关系

它们本质上都是“条件等待机制”。

### 8.1 对应关系

| monitor 机制 | Lock/AQS 机制 |
|---|---|
| `synchronized(obj)` | `lock.lock()` |
| `obj.wait()` | `condition.await()` |
| `obj.notify()` | `condition.signal()` |
| `obj.notifyAll()` | `condition.signalAll()` |

---

### 8.2 共同语义

不管是 monitor 还是 `Condition`，逻辑都是：

1. 当前线程先持有锁
2. 条件不满足
3. 释放锁并进入条件等待队列
4. 被通知后重新竞争锁
5. 重新拿到锁之后再继续执行

---

### 8.3 `Condition` 更灵活的原因

`wait/notify` 依附在对象 monitor 上，通常一把锁只有一套等待集合语义。

而 `Condition` 可以让一把 `Lock` 派生出多个条件队列：

```java
Condition notFull = lock.newCondition();
Condition notEmpty = lock.newCondition();
```

这在生产者消费者等场景里更灵活，因为：

- 生产者可以等待 `notFull`
- 消费者可以等待 `notEmpty`
- 通知时也能更精确地唤醒对应类型的线程

---

## 9. 为什么 `wait()` / `await()` 必须写在 `while` 里

这一节保留判断原则。更完整的 `wait()` 时序和线程视角，见 [wait-notify.md](./wait-notify.md)。

标准写法：

```java
synchronized (lock) {
    while (!condition) {
        lock.wait();
    }
}
```

或者：

```java
lock.lock();
try {
    while (!condition) {
        condition.await();
    }
} finally {
    lock.unlock();
}
```

不能写成简单的 `if`，原因至少有三个：

### 9.1 伪唤醒

线程可能在没有明确 `notify` / `signal` 的情况下从等待中返回。

因此：

- 醒来不代表条件一定满足
- 必须重新判断条件

---

### 9.2 醒来时条件可能已经又不成立了

即使通知发生过，也不代表线程真正继续执行时条件仍然满足。

典型过程：

1. 线程 A 在等条件成立
2. 线程 B 修改条件并通知
3. A 被唤醒，但还没抢到锁
4. 线程 C 先抢到锁，把条件又改回去了
5. A 最终拿到锁时，条件已经不满足

所以醒来后必须重新检查条件。

### 9.3 `notifyAll()` / `signalAll()` 会唤醒很多线程

很多线程会同时醒来，但真正能继续执行的通常只有一部分，甚至只有一个。

所以每个线程都必须在继续执行前再次确认条件。

### 9.4 `if` 的问题

`if` 只检查一次条件：

```java
if (!condition) {
    wait();
}
```

醒来后默认条件已经满足，这个假设在并发环境里是站不住的。

而 `while` 的语义是：

- 只要条件不满足，就继续等
- 醒来只是获得一次重新检查的机会

---

## 10. `sleep()` 和 `wait()` 的区别

这一节保留最核心对比。更详细的解释和时序，见 [wait-notify.md](./wait-notify.md)。

| 维度 | `sleep()` | `wait()` |
|---|---|---|
| 所属类 | `Thread` | `Object` |
| 是否必须在同步块中 | 否 | 是 |
| 是否释放锁 | 否 | 是 |
| 主要用途 | 暂停线程一段时间 | 条件等待 / 线程协作 |
| 唤醒方式 | 时间到 / 中断 | `notify` / `notifyAll` / 超时 / 中断 / 伪唤醒 |
| 是否要求持有 monitor | 否 | 是 |

---

## 11. 面试里可以怎么组织回答

如果面试官问：

> 说说 `synchronized` 的实现原理

可以按下面的顺序讲。

### 11.1 第一步：先讲源码层

`synchronized` 有三种常见形式：

- 同步代码块锁指定对象
- 同步实例方法锁 `this`
- 同步静态方法锁 `Class` 对象

---

### 11.2 第二步：再讲字节码层

- 同步代码块会生成 `monitorenter` / `monitorexit`
- 同步方法不会直接生成这两个指令，而是通过 `ACC_SYNCHRONIZED` 让 JVM 在方法调用前后处理加解锁

---

### 11.3 第三步：讲运行时 monitor

- 两种字节码形式最终都会落到 monitor
- HotSpot 会结合对象头中的 Mark Word 来判断锁状态
- 经典实现里常讲偏向锁、轻量级锁、重量级锁
- 新版本 HotSpot 实现细节有演进，但 monitor 语义不变

---

### 11.4 第四步：讲可重入

- monitor 会记录 owner 和重入次数
- 所以 `synchronized` 是可重入锁

---

### 11.5 第五步：讲 `wait/notify`

- `wait()` 会释放当前 monitor 并进入等待集合
- `notify()` / `notifyAll()` 只是通知等待线程重新竞争锁
- `wait()` 返回前必须重新拿到锁

---

### 11.6 第六步：讲实践细节

- `wait()` 必须写在 `while` 中，不能写成 `if`
- 原因包括伪唤醒、条件变化、批量唤醒后的再次检查
- `sleep()` 不释放锁，`wait()` 会释放锁

---

## 12. 3 分钟标准回答版

下面这段话可以直接作为面试回答模板：

`synchronized` 从源码层面分三种：同步代码块锁指定对象，同步实例方法锁 `this`，同步静态方法锁 `Class` 对象。到了字节码层，同步代码块会编译成 `monitorenter/monitorexit`，而同步方法不会生成这两个指令，而是通过方法访问标志 `ACC_SYNCHRONIZED` 让 JVM 在方法调用前后隐式加解锁。

运行时层面，它们最终都落到 JVM 的 monitor 机制上。HotSpot 会结合对象头里的 Mark Word 来管理锁状态。经典 JDK 8 里常讲偏向锁、轻量级锁、重量级锁，不过新版本 HotSpot 这部分实现已经演进了，但 monitor 的语义没变。monitor 会记录 owner 和重入次数，所以 `synchronized` 是可重入的。

`wait/notify` 也是 monitor 机制的一部分。`wait` 必须在持有锁时调用，因为它要原子地完成保存重入次数、释放锁、进入等待队列、被唤醒后重新竞争锁并恢复状态这几个步骤。`notify/notifyAll` 只是通知等待线程去重新竞争锁，不是让它们立刻执行。所以 `wait` 一定要写在 `while` 里，因为线程醒来后条件未必还成立，甚至可能发生伪唤醒。另外，`sleep` 不释放锁，而 `wait` 会释放锁，所以 `sleep` 适合时间暂停，`wait` 更适合线程协作。

---

## 13. 高频追问与简答

### 13.1 同步代码块和同步方法有啥区别

- 语义上都能实现同步
- 字节码表示不同
- 同步代码块用 `monitorenter/monitorexit`
- 同步方法用 `ACC_SYNCHRONIZED`

---

### 13.2 实例同步方法和静态同步方法是同一把锁吗

不是。

- 实例同步方法锁的是 `this`
- 静态同步方法锁的是 `Class` 对象

它们默认不会互斥，因为锁对象不同。

---

### 13.3 `notify()` 之后线程会立刻运行吗

不会。

- 被通知的线程只是获得重新竞争锁的资格
- 只有重新拿到 monitor 后，`wait()` 才真正返回

---

### 13.4 为什么 `wait` 必须写 `while`

- 可能有伪唤醒
- 被唤醒时条件可能又不成立
- `notifyAll()` 可能唤醒很多并不满足条件的线程

---

### 13.5 `sleep()` 和 `wait()` 最核心的区别是什么

- `sleep()` 只让当前线程暂停，不释放锁
- `wait()` 会释放 monitor，并参与条件等待

---

## 14. 参考资料

- [JEP 374: Deprecate and Disable Biased Locking](https://openjdk.org/jeps/374)
- [JDK-8301897: Obsoleted Biased-Locking Related Options](https://bugs.openjdk.org/browse/JDK-8301897)
- [JDK-8327089: Change LockingMode Default to LM_LIGHTWEIGHT](https://bugs.openjdk.org/browse/JDK-8327089)
- [JEP 450: Compact Object Headers](https://openjdk.org/jeps/450)
- [OpenJDK Wiki: Synchronization Using The ObjectMonitorTable](https://wiki.openjdk.org/spaces/HotSpot/pages/138215471/Synchronization%2BUsing%2BThe%2BObjectMonitorTable)
- [HotSpot `markWord.hpp`](https://github.com/openjdk/jdk/blob/master/src/hotspot/share/oops/markWord.hpp)
- [HotSpot `objectMonitor.hpp`](https://github.com/openjdk/jdk/blob/master/src/hotspot/share/runtime/objectMonitor.hpp)
- [HotSpot `objectMonitor.cpp`](https://github.com/openjdk/jdk/blob/master/src/hotspot/share/runtime/objectMonitor.cpp)
