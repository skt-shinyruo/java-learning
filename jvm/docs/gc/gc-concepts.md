# JVM GC 基础：类型、算法与收集器

GC 是 Java 垃圾回收（Garbage Collection），核心作用是：JVM 自动回收不再被引用的对象占用的内存。不同 GC 名称通常表示“回收哪个区域、触发成本多大、是否影响整个堆”。

常见概念如下：

| 名称 | 主要回收区域 | 含义 |
| --- | --- | --- |
| Minor GC / Young GC | 新生代（Young Generation） | 回收新创建、生命周期短的对象 |
| Major GC | 通常指老年代（Old Generation） | 回收老年代对象，但不同 JVM、不同日志工具里的含义不完全统一 |
| Full GC | 整个 Java 堆，通常还包括元空间/方法区相关检查 | 成本最高，通常会造成明显 Stop-The-World |
| Mixed GC | G1 中常见 | 同时回收整个新生代和一部分老年代 Region |
| Metaspace GC | 元空间压力触发 | 类元数据太多时触发，可能伴随 Full GC |

---

## 1. Minor GC / Young GC

Java 堆通常可以按分代模型理解：

```text
Heap
├── Young Generation 新生代
│   ├── Eden
│   ├── Survivor 0
│   └── Survivor 1
└── Old Generation 老年代
```

大多数对象先分配在 `Eden`。当 `Eden` 空间不够时，会触发 Minor GC，也常叫 Young GC。

Minor GC 会清理新生代里已经不可达的对象；仍然存活的对象会被移动到 Survivor 区，多次 GC 后还活着的对象会晋升到老年代。

特点：

- 触发频繁。
- 通常速度较快。
- 一般会 Stop-The-World，但暂停时间较短。
- 主要处理“朝生夕死”的对象。

## 2. Major GC

Major GC 这个词有点混乱。很多资料里说它是“老年代 GC”，但不同垃圾收集器、不同日志工具里可能把它和 Full GC 混用。

一般可以这样理解：

- 主要回收老年代。
- 成本通常高于 Minor GC。
- 可能伴随 Full GC。
- 老年代空间不足、晋升失败等场景可能触发。

排查日志时，不建议只看 `Major GC` 这个词，要看具体 GC 日志里回收了哪些区域、触发原因是什么、暂停阶段是什么。

## 3. Full GC

Full GC 通常是最重的一类 GC，可能回收：

- 新生代。
- 老年代。
- 元空间（Metaspace）。
- 类卸载、方法区相关数据。

常见触发原因：

- 老年代空间不足。
- 元空间不足。
- `System.gc()` 被显式调用。
- Minor GC 后对象晋升失败。
- 大对象分配失败。
- CMS、G1 等收集器发生失败退化。

特点：

- 通常 Stop-The-World 时间更长。
- 对线上服务影响较大。
- 如果频繁 Full GC，通常说明内存配置、对象生命周期、缓存、类加载或泄漏存在问题。

## 4. Mixed GC

Mixed GC 是 G1 垃圾收集器里常见的概念。

G1 把堆拆成很多 Region。Mixed GC 不只回收新生代 Region，还会选择一部分“垃圾比例较高”的老年代 Region 一起回收。

特点：

- 不是一次性回收整个老年代。
- 目标是把老年代回收拆成多次可控暂停。
- 常见于 G1 的并发标记完成之后。

### 4.1 G1 Remembered Set

G1 的 **Remembered Set** 通常简称 **RSet**。它是每个 Region 维护的一份“外部引用索引”，用来记录哪些其他 Region 的 card 里可能有引用指向当前 Region。

#### 4.1.1 Card 和 Card Table

`card` 可以理解成 Java 堆里一小块固定大小的内存区域。在 HotSpot 中，一个 card 通常是 512 字节。G1 的 Region 会继续被切成很多 card：

先把结构拆开看：

```text
Java Heap
├── Region A
│   ├── card A1
│   ├── card A2
│   └── card A3
└── Region B
    ├── card B1
    └── card B2
```

`card table` 是 JVM 维护的一张辅助表，用来记录每个 card 的状态。它不是 Java 对象，也不是业务数据结构，而是 GC 用来缩小扫描范围的元数据。

可以粗略理解为：

```text
Java Heap:
+---------+---------+---------+---------+
| card 0  | card 1  | card 2  | card 3  |
+---------+---------+---------+---------+

Card Table:
+----+----+----+----+
| 0  | 1  | 0  | 0  |
+----+----+----+----+
```

其中 `1` 可以理解成这个 card 被标记为 dirty，表示这块内存里的引用字段发生过写入，后续需要被检查。card table 的粒度比对象引用粗：它只知道“这一小块内存可能有需要处理的引用”，不直接记录“哪个对象字段精确引用了哪个对象”。

如果 `Region A` 的某个对象字段引用了 `Region B` 里的对象：

```text
Region A / card A2 里的 obj1.field -> Region B 里的 obj2
```

那么 `Region B` 的 RSet 会记录：

```text
Region B 的 RSet:
  Region A / card A2 可能有引用指向我
```

RSet 记录的不是“对象 `obj1` 精确引用了对象 `obj2`”这种完整对象关系，而是来源 card。GC 真正扫描时，会再进入这些 card，找出其中实际指向目标 Region 的引用。

#### 4.1.2 写屏障如何维护 RSet

RSet 主要靠写屏障维护。当 Java 代码执行字段赋值或数组引用写入时：

```java
obj1.field = obj2;
```

假设 `obj1` 在 `Region A`，`obj2` 在 `Region B`，赋值之后的引用关系是：

```text
Region A                         Region B
+-------------------+             +-------------------+
| obj1              |             | obj2              |
|   field ----------+-----------> |                   |
+-------------------+             +-------------------+
```

真正被修改的位置是 `obj1.field` 这个引用槽位。`obj2` 本身没有被修改，它只是成为了这个引用槽位里保存的新引用值。

JVM 会在写入路径上插入 G1 的 post-write barrier。用伪代码表示，大致是：

```java
obj1.field = obj2;

if (obj2 != null) {
    Region sourceRegion = regionOf(addressOf(obj1.field));
    Region targetRegion = regionOf(obj2);

    if (sourceRegion != targetRegion) {
        Card sourceCard = cardOf(addressOf(obj1.field));
        markDirty(sourceCard);
        enqueueDirtyCard(sourceCard);
    }
}
```

更准确地说，dirty 的不是“`obj2` 所在 card”，而是“被写入的引用槽位所在 card”。通常可以简化说成 `obj1` 所在 card，但如果对象跨 card，真正标记的是 `obj1.field` 这个字段地址所在的 card。

原因是引用关系存放在 source 端。`obj1.field = obj2` 这条引用边实际存在于 `obj1.field` 这个内存位置：

```text
Region A 的某个 card 里，有一个引用字段指向 Region B
```

因此 dirty card 标记的是 `Region A` 的来源 card。后续扫描这个来源 card，才能发现它里面的引用字段指向了哪个目标 Region。

写屏障通常不会直接做完整扫描，否则每次引用写入都会太重。G1 会把 dirty card 放入 dirty card queue，再让 refinement threads 异步消费：

```text
取出 dirty card
扫描这个 card 里的引用字段
如果发现引用指向 Region B
  把这个来源 card 加到 Region B 的 RSet
```

也就是说，流程分成两步：

```text
写屏障快速记录：
  Region A 的 card A2 dirty 了
  把 card A2 放进 dirty card queue

后台 refinement 线程整理：
  取出 card A2
  扫描 card A2 里的引用字段
  发现 obj1.field 指向 Region B
  把 card A2 记录到 Region B 的 RSet
```

所以 dirty card 和 RSet 更新不是同一个动作。dirty card 是“这块来源内存需要后续检查”的临时标记；RSet 是目标 Region 维护的外部引用索引。

如果错误地标记 `obj2` 所在 card，后续扫描的是 `Region B` 里面的对象，只能看到 `obj2` 自己的字段引用了谁，看不到 `Region A` 里的 `obj1.field` 正在引用 `obj2`。这对维护 `Region B` 的入边索引没有帮助。

所以 RSet 是程序运行过程中逐步维护出来的，不是 GC 暂停时临时全堆计算出来的。

#### 4.1.3 GC 时如何使用 RSet

有了 RSet，G1 回收某个 Region 时就不需要问“整个堆里有没有对象引用我”。它只需要看这个 Region 的 RSet：

```text
Region B 的 RSet:
  Region A / card A2
  Region C / card C5
```

GC 只扫描这些被记录的外部 card，找出真正指向 `Region B` 的引用，并把它们作为外部根引用处理。这样就避免了为了回收一个或一组 Region 而扫描整个老年代或整个堆。

Mixed GC 依赖这个能力。一次 Mixed GC 会选择年轻代 Region 和一部分老年代 Region 组成 Collection Set：

```text
Collection Set:
  Eden Region 1
  Eden Region 2
  Old Region 8
  Old Region 13
```

对于 Collection Set 里的 Region，G1 通过对应 RSet 找到其他 Region 指向它们的外部引用，然后疏散这些 Region 里的存活对象。因为外部引用入口已经由 RSet 缩小到少量 card，G1 才能只回收部分老年代 Region，而不是每次都做整堆回收。

一句话概括：

```text
写屏障发现跨 Region 引用；
dirty card queue 和 refinement threads 把来源 card 整理进目标 Region 的 RSet；
GC 时通过 RSet 只扫描可能引用回收 Region 的外部 card；
因此 G1 可以做 Region 粒度的局部回收和 Mixed GC。
```

## 5. 简单对比

```text
Minor GC:
只看年轻代，频繁但通常快。

Major GC:
通常指老年代回收，但术语不稳定。

Full GC:
整个堆级别的大回收，成本最高，重点关注。

Mixed GC:
G1 里新生代 + 部分老年代的组合回收。
```

一句话记忆：

```text
Minor GC 清年轻代；
Major GC 清老年代；
Full GC 清整个堆；
Mixed GC 是 G1 的“年轻代 + 部分老年代”。
```

## 6. 垃圾回收算法和垃圾收集器的关系

学习 GC 时最容易混在一起的是三类概念：

| 层次 | 代表概念 | 说明 |
| --- | --- | --- |
| 判断对象死活 | 引用计数、可达性分析 | 判断对象是否仍应保留 |
| 垃圾回收算法 | 标记-清除、复制、标记-整理、分代收集 | 回收内存的基本方法或组合策略 |
| 垃圾收集器 | Serial、ParNew、Parallel、CMS、G1、ZGC | HotSpot JVM 中真正执行 GC 的工程实现 |

可以这样理解：

```text
垃圾回收算法 = 理论方法
垃圾收集器 = JVM 对这些方法的工程实现和组合
```

所以，`Serial`、`CMS`、`G1` 这些收集器会使用标记-清除、复制、标记-整理等基础算法，但通常不是一个收集器只等于一个算法，而是结合分代、并行、并发、Region 管理和停顿控制等策略一起实现。

## 7. 判断对象死活：引用计数与可达性分析

垃圾回收首先要回答一个问题：哪些对象还活着，哪些对象已经可以回收。

| 算法 | 做法 | 备注 |
| --- | --- | --- |
| 引用计数法 | 每个对象记录有多少引用指向它，计数为 0 就可回收 | 实现直观，但处理循环引用比较麻烦 |
| 可达性分析 | 从 `GC Roots` 出发，能走到的是存活对象，走不到的是垃圾 | Java 主流 JVM 使用的思路 |

Java GC 的主线是：

```text
从 GC Roots 出发
  -> 沿着对象引用关系向下搜索
  -> 可达对象存活
  -> 不可达对象可以回收
```

常见 `GC Roots` 包括线程栈中的引用、静态字段引用、JNI 引用、JVM 内部句柄等。更细的根枚举和并发标记问题见 [HotSpot OopMap 与 GC Roots](oopmap-gc-roots.md)。

## 8. 基础垃圾回收算法

### 8.1 标记-清除

标记-清除分两步：

```text
标记存活对象
  -> 清除未标记对象
```

优点是实现相对简单；问题是清除后容易留下不连续的空闲空间，也就是内存碎片。碎片多了以后，即使总空闲内存够，也可能找不到一块连续空间分配大对象。

### 8.2 复制算法

复制算法把内存分成两块或多个区域，回收时把存活对象复制到新的空闲区域，然后把旧区域整体清空。

典型年轻代可以这样理解：

```text
Eden + From Survivor
  -> 存活对象复制到 To Survivor 或老年代
  -> 清空 Eden 和 From Survivor
```

复制算法的优点是回收快、没有碎片；缺点是需要预留可复制的空间。如果对象存活率很高，复制成本会变大，所以它更适合年轻代这类对象多数朝生夕死的区域。

### 8.3 标记-整理

标记-整理也会先标记存活对象，但不会只清除垃圾对象，而是把存活对象向一端移动：

```text
标记存活对象
  -> 移动存活对象
  -> 清理边界外空间
```

它的优点是不会产生明显内存碎片；缺点是移动对象和更新引用的成本更高，通常停顿时间也会更明显。

### 8.4 对象移动带来的问题

复制算法和标记-整理都会移动对象。移动对象的核心代价是：

```text
对象地址变了，所有指向这个对象的引用都必须跟着更新。
```

例如移动前：

```text
局部变量 x    -> 对象 A 地址 0x1000
对象 B.field -> 对象 A 地址 0x1000
静态变量 C.ref -> 对象 A 地址 0x1000
```

GC 把对象 A 从 `0x1000` 移动到 `0x8000` 之后，这些引用都必须更新：

```text
局部变量 x    -> 0x8000
对象 B.field -> 0x8000
静态变量 C.ref -> 0x8000
```

如果某个引用还指向旧地址，就可能访问到已经无效的内存，或者访问到旧地址被复用后的其他对象。Java 代码通常感知不到对象真实地址变化，因为引用由 JVM 管理；但 JVM 内部必须保证所有活跃引用都能被准确找到和正确更新。

这会带来几个工程问题：

- **必须准确找到所有引用**：线程栈、寄存器、对象字段、静态字段、JNI 引用、JVM 内部句柄等位置都可能保存对象引用。HotSpot 需要 OopMap、GC Roots 枚举和对象元数据来区分“这是对象引用”还是“只是普通整数”。
- **移动期间通常需要 Stop-The-World**：最简单安全的方式是暂停 Java 线程，移动对象并更新引用，再恢复 Java 线程。否则用户线程可能一边访问对象，GC 一边搬迁对象。
- **并发移动需要屏障机制**：ZGC、Shenandoah 这类低延迟收集器会尽量并发移动对象，但需要 Load Barrier、Store Barrier、转发表、染色指针等机制来发现旧引用并修正到新地址。
- **移动本身有成本**：复制对象内容、更新引用、维护转发表、处理跨代和跨 Region 引用、维护 Remembered Set 或 Card Table 都会消耗 CPU。
- **JNI 和本地代码更麻烦**：普通 Java 引用可以由 JVM 更新，但 native 代码如果直接持有对象地址，就需要通过 JNI Handle、临时固定对象或其他约束来保证安全。

所以三种基础算法的取舍可以这样看：

```text
标记-清除：
不移动对象，引用不用改，但会产生内存碎片。

复制算法：
移动对象，回收快、无碎片，但要更新引用。

标记-整理：
移动对象，解决碎片，但整理和引用更新成本高。
```

### 8.5 分代收集

分代收集不是单一回收算法，而是一种组合策略。它基于一个经验：多数对象生命周期很短，少数对象会长期存活。

常见组合是：

```text
年轻代：复制算法
老年代：标记-清除 或 标记-整理
```

年轻代对象死亡率高，复制少量存活对象很划算；老年代对象存活率高，大量复制反而不划算，因此更常使用标记-清除或标记-整理。

## 9. 经典 HotSpot 垃圾收集器

下面这些是学习 HotSpot GC 时最经典的一组收集器。

| 收集器 | 主要区域 | 主要目标 | 常见算法 |
| --- | --- | --- | --- |
| Serial | 年轻代 | 简单、小堆 | 复制算法 |
| Serial Old | 老年代 | 简单、小堆 | 标记-整理 |
| ParNew | 年轻代 | CMS 的年轻代搭档 | 复制算法 |
| Parallel Scavenge | 年轻代 | 吞吐量优先 | 复制算法 |
| Parallel Old | 老年代 | 吞吐量优先 | 标记-整理 |
| CMS | 老年代 | 降低停顿 | 标记-清除 |
| G1 | 整堆 Region | 可控停顿、分区回收 | 标记 + 复制/疏散 |
| ZGC | 整堆 Region/ZPage | 超低停顿 | 并发标记、并发整理 |
| Shenandoah | 整堆 Region | 超低停顿 | 并发标记、并发整理 |
| Epsilon | 整堆 | 测试，不回收 | 无回收 |

其中，Java 8 学习和排查中最常见的是：

```text
Serial
ParNew
Parallel Scavenge
Serial Old
Parallel Old
CMS
G1
```

CMS 在 JDK 9 被标记为废弃，JDK 14 被移除；G1 从 JDK 9 开始成为 HotSpot 的默认垃圾收集器。ZGC 和 Shenandoah 更偏现代低延迟场景，ZGC 的详细机制见 [ZGC 收集器](zgc.md)。

## 10. 常见收集器组合

垃圾收集器通常按年轻代和老年代组合使用。几个经典组合如下：

```text
Serial + Serial Old
```

适合小堆、单核或客户端式场景。GC 时会 Stop-The-World，且 Serial 系列使用单线程回收。

```text
ParNew + CMS
```

CMS 是老年代收集器，年轻代通常搭配 ParNew。ParNew 负责年轻代复制回收，CMS 负责老年代并发标记清除。

```text
Parallel Scavenge + Parallel Old
```

吞吐量优先组合。适合后台计算、批处理等更看重总吞吐而不是极低停顿的场景。

```text
G1
```

G1 自己覆盖年轻代和老年代。它把堆拆成多个 Region，Young GC 回收年轻代 Region，Mixed GC 在回收年轻代的同时选择一部分垃圾比例高的老年代 Region。

一句话总结：

```text
标记-清除 / 复制 / 标记-整理 是基础算法；
Serial / CMS / G1 是 JVM 里的具体垃圾收集器；
垃圾收集器会组合这些算法，并加入分代、并发、并行、Region 和停顿控制等工程策略。
```
