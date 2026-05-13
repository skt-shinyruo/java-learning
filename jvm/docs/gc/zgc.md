# ZGC 收集器

ZGC 是 HotSpot/OpenJDK 中面向低延迟场景的垃圾收集器。它的核心目标不是让 GC 总 CPU 开销最低，而是尽量把 Stop The World 停顿压到很短：大部分标记、搬迁、引用修正工作都和 Java 用户线程并发执行。

一句话版：

```text
ZGC = Region 化堆布局 + 并发标记 + 并发整理 + 染色指针 + Load Barrier
```

理解 ZGC 时不要先背阶段名，先抓住一个问题：

```text
对象已经被 GC 搬走了，但 Java 线程手里还可能拿着旧地址，怎么办？
```

ZGC 的答案是：引用里带状态，读引用时检查，发现旧引用就自动转到新地址，并顺手修正这个引用。

---

## 1. ZGC 解决的核心问题

普通压缩式 GC 可以简化成：

```text
暂停 Java 线程
  -> 从 GC Roots 出发找活对象
  -> 把活对象搬到新位置
  -> 更新所有指向这些对象的引用
  -> 释放旧空间
恢复 Java 线程
```

这种方案实现直观，但停顿时间容易随堆大小、活对象数量、引用数量增加而变长。

ZGC 的思路是把这些工作拆开，让多数工作和 Java 线程一起跑：

```text
短暂停顿：扫描 GC Roots
并发标记：找活对象
并发重分配：移动活对象
并发重映射：修正旧引用
```

因此，ZGC 不是完全没有停顿，而是把停顿限制在很小的阶段上。OpenJDK JEP 333 对早期 ZGC 的目标是停顿不超过 10ms；JDK 21 的分代 ZGC 进一步把典型目标描述为 1ms 以内。

---

## 2. Region / ZPage：ZGC 的堆不是一整块连续整理

ZGC 和 G1、Shenandoah 一样，不把 Java 堆当成一个整体连续区域来回收，而是拆成很多区域。ZGC 的资料里常把这些区域称为 **ZPage**，也可以按书里的说法理解为 Region。

ZGC 会按对象大小使用不同规格的区域：

- 小对象进入小型区域。
- 中等对象进入中型区域。
- 特别大的对象进入大型区域，大型区域通常只服务一个大对象。

Region 化的意义是：ZGC 可以只挑选回收价值高的区域做整理，而不是每次都整理整个堆。

例如：

```text
Heap
  R1: 活对象少，垃圾多    <- 适合整理
  R2: 活对象很多          <- 暂时不动
  R3: 空闲                <- 可以接收搬迁后的对象
  R4: 活对象少，垃圾多    <- 适合整理
```

ZGC 的重分配阶段会把 R1、R4 中还活着的对象复制到其他可用区域中，然后释放 R1、R4。

---

## 3. 染色指针：把 GC 状态放进引用里

普通对象引用可以粗略理解成：

```text
reference = object_address
```

ZGC 的染色指针可以粗略理解成：

```text
colored_reference = object_address + gc_state_bits
```

这里的“颜色”不是给对象本体上色，而是给**指向对象的引用**附加状态。ZGC 可以通过引用本身判断：

- 这个引用在当前标记周期中是否已经被标记。
- 这个引用指向的地址是否还是正确地址。
- 这个引用指向的对象是否处于被搬迁的集合中。
- 读到这个引用时是否需要执行额外处理。

换句话说，ZGC 不只是知道：

```text
这个引用指向哪里？
```

它还知道：

```text
这个引用现在是不是干净的？
```

这就是染色指针的价值。

---

## 4. 为什么 64 位指针里能放状态位

64 位平台的指针理论上有 64 位，但真实硬件和操作系统并不会把所有位都拿来做有效寻址。ZGC 利用这类未被完整用于寻址的位，把少量 GC 元数据编码进引用。

可以简化理解为：

```text
64-bit oop
  高位中的一部分：GC 状态
  低位中的一部分：对象地址
```

这不是 Java 语言层面的能力，而是 HotSpot、CPU 地址空间、操作系统虚拟内存配合出来的实现细节。Java 程序仍然只看到普通引用，看不到这些标志位。

需要注意：

- 早期 ZGC 不支持压缩普通对象指针，因为它需要 64 位引用来存放地址和状态。
- JDK 15 时 ZGC 最大堆从早期的 4TB 提升到 16TB。
- 具体位布局属于实现细节，不建议把某一版图示当成永远固定的规范。

---

## 5. Load Barrier：读引用时做一次轻量检查

Load Barrier 可以理解成 JVM 插在“读取对象引用”旁边的一小段逻辑。

Java 代码看起来是：

```java
Object x = a.ref;
```

在 ZGC 的语境下，可以粗略理解成：

```text
raw = load a.ref
if raw 是正常引用:
    return raw
else:
    fixed = 修正这个引用
    a.ref = fixed
    return fixed
```

绝大多数时候引用是正常的，Load Barrier 走快速路径，成本很低。只有读到需要处理的引用时，才进入慢路径。

这个屏障是 ZGC 能并发搬迁对象的关键。因为 Java 线程可能在 GC 搬对象期间继续读字段、读数组元素、读栈上保存的对象引用，ZGC 必须保证这些读取拿到的是正确对象。

---

## 6. 自愈：旧引用第一次被读到时自动修好

假设堆里一开始是这样：

```text
A.ref -> X

Region R1:
  X
```

ZGC 决定整理 R1，于是把活对象 `X` 复制到新区域：

```text
Region R1:
  X     // 旧位置

Region R3:
  X'    // 新位置
```

同时，ZGC 为 R1 建立转发表：

```text
Forward Table for R1:
  old X -> new X'
```

注意，此时 `A.ref` 可能还没来得及更新：

```text
A.ref -> X     // 旧引用
```

当 Java 线程之后执行：

```java
Object x = A.ref;
```

Load Barrier 会发现 `A.ref` 是旧引用，于是：

```text
1. 根据旧地址找到所属 Region R1
2. 查 R1 的转发表：old X -> new X'
3. 返回 X'
4. 顺手把 A.ref 改成 X'
```

修正后变成：

```text
A.ref -> X'
```

这就是 ZGC 说的自愈。旧引用第一次被访问时慢一次，之后这个引用就是新地址了。

---

## 7. 为什么 ZGC 可以更早释放旧 Region

这是 ZGC 和 Shenandoah 对比时最容易卡住的点。

Shenandoah 早期模式可以粗略理解为：

```text
对象搬走
  -> 堆中仍可能有很多旧引用
  -> 等引用更新阶段把旧引用修完
  -> 再释放旧 Region
```

ZGC 可以粗略理解为：

```text
对象搬走
  -> 旧引用即使还存在，也能靠 Load Barrier 自愈
  -> 旧 Region 的对象内容可以更早释放和复用
  -> 转发表暂时保留，供旧引用自愈时查询
```

关键区别在于：ZGC 不要求“所有指向旧 Region 的引用都已经修正完”，才释放旧 Region 的对象空间。只要活对象已经被复制走，并且旧地址到新地址的转发表还在，旧引用后续被读取时就可以找到新对象。

所以书里那段话的意思是：

```text
Shenandoah:
  搬迁期间旧副本和新副本可能同时占用大量空间。
  如果几乎所有对象都存活，极端情况下需要接近 1:1 的额外空间来容纳新副本。

ZGC:
  一个 Region 搬完后就能更早释放对象空间，释放出来的空间又可以参与后续搬迁。
  理论上空间周转压力更小。
```

这不是说 ZGC 永远只需要一个空闲 Region 就能在真实负载下顺利运行。真实系统还要考虑分配速率、GC 线程追赶速度、转发表、元数据、碎片和安全余量。但从并发整理算法的空间周转模型看，ZGC 的染色指针和自愈机制确实让旧 Region 的复用更灵活。

---

## 8. ZGC 的一次收集过程

可以把 ZGC 的周期记成四个大阶段。

### 8.1 并发标记

ZGC 从 GC Roots 出发找活对象。这个阶段大部分时间和 Java 线程并发运行，中间会有很短的停顿来处理根集合和阶段切换。

普通三色标记常说“给对象染色”，但 ZGC 更准确的理解是：

```text
在引用上记录标记状态
```

标记阶段会更新染色指针里的标记位，判断哪些对象仍然可达。

### 8.2 并发预备重分配

标记结束后，ZGC 知道哪些对象活着，也知道哪些 Region 垃圾多、整理收益高。

这个阶段会选择要整理的 Region，形成重分配集：

```text
Relocation Set:
  R1
  R4
  R8
```

### 8.3 并发重分配

ZGC 把重分配集中的活对象复制到新的 Region，同时为旧 Region 建立转发表：

```text
old address -> new address
```

Java 线程仍然可以运行。如果 Java 线程读到了旧引用，Load Barrier 会通过转发表找到新对象，并执行自愈。

### 8.4 并发重映射

重映射的目标是把堆中残留的旧引用批量改成新引用。

但是对 ZGC 来说，这一步不紧急，因为旧引用被读到时会自愈。重映射主要是为了：

- 减少未来访问旧引用时的慢路径开销。
- 尽早释放转发表等辅助结构。
- 让下一轮 GC 的引用状态更干净。

ZGC 可以把一部分重映射工作合并到下一次并发标记中，避免单独多扫一遍完整对象图。

---

## 9. 和 G1、Shenandoah 的区别

### 9.1 和 G1

G1 也是 Region 化收集器，也能做并发标记。但 G1 的对象疏散阶段通常需要 Stop The World：

```text
G1:
  并发标记
  STW Evacuation
  更新引用
```

所以 G1 的停顿时间会受到回收集大小、活对象数量、对象复制成本影响。

ZGC 的关键优势是对象搬迁也能并发进行：

```text
ZGC:
  并发标记
  并发搬迁
  并发修引用
```

### 9.2 和 Shenandoah

Shenandoah 也追求并发整理。早期 Shenandoah 通过 Brooks Pointer 和读屏障处理对象搬迁。

可以粗略对比：

```text
Shenandoah:
  对象访问通过转发指针找到当前位置。
  引用更新阶段完成后，回收集中的旧 Region 才能放心释放。

ZGC:
  引用本身携带状态。
  Load Barrier 发现旧引用时按转发表自愈。
  旧 Region 的对象空间可以更早复用。
```

因此，ZGC 的设计重点是让“引用是否需要修正”这件事能从引用本身看出来，并在读取引用时完成修正。

---

## 10. 分代 ZGC：书里和现代 JDK 的差异

《深入理解 Java 虚拟机》第 3 版讲 ZGC 时，主要基于 JDK 11 前后的非分代 ZGC。当时可以这样概括：

```text
非分代 ZGC:
  单代
  Region 化
  colored pointers
  load barriers
  没有传统分代收集的跨代引用维护
```

但 OpenJDK 后续已经演进：

- JDK 11：ZGC 作为实验特性引入。
- JDK 15：ZGC 成为生产特性，使用 `-XX:+UseZGC` 不再需要解锁实验特性。
- JDK 21：引入分代 ZGC，可用 `-XX:+UseZGC -XX:+ZGenerational` 启用。
- JDK 23：ZGC 默认使用分代模式。
- JDK 24：移除非分代模式，`-XX:+UseZGC` 使用分代 ZGC。

分代 ZGC 仍然保留 ZGC 的核心低延迟设计，但把堆逻辑上分成年轻代和老年代：

```text
Young Generation:
  新对象多
  死亡率高
  更频繁收集

Old Generation:
  长寿对象多
  死亡率低
  较少收集
```

这样做是为了适配分代假说：大多数对象朝生夕死。只收年轻代通常比每次都处理整个堆更划算。

分代之后，ZGC 需要处理老年代到年轻代的引用，因此现代分代 ZGC 除了 Load Barrier，也会使用 Store Barrier 等机制维护跨代引用。也就是说，书里“ZGC 不用写屏障”的表述适用于早期非分代 ZGC，不应直接套到 JDK 23+ 的默认分代 ZGC。

---

## 11. 什么时候适合用 ZGC

ZGC 适合：

- 服务端应用对尾延迟敏感，例如接口超时、交易、网关、搜索、在线推荐。
- 堆较大，不能接受 G1 或 Parallel GC 的较长停顿。
- 更愿意用一定 CPU 和内存余量换取稳定低停顿。

ZGC 不一定适合：

- 极端追求吞吐量、对停顿不敏感的离线任务。
- CPU 资源非常紧张，无法给并发 GC 线程留空间。
- 分配速度长期高于 ZGC 回收速度的应用，这会导致 allocation stall 风险。

如果使用 ZGC，至少要关注 GC 日志中的：

- Pause 时间是否符合预期。
- Allocation Stall 是否出现。
- 并发周期是否能及时释放足够空间。
- 堆余量是否足够覆盖应用分配峰值。

---

## 12. 一句话总结

ZGC 的低停顿来自一个核心设计：

```text
对象可以并发搬迁；
引用不必立刻全堆修完；
读到旧引用时，Load Barrier 通过染色指针和转发表自动修正。
```

如果只记三个关键词，就是：

```text
染色指针
Load Barrier
自愈
```

它们共同解决了并发整理最难的问题：对象地址变了，Java 线程仍然能安全访问对象。

---

## 参考

- [JEP 333: ZGC: A Scalable Low-Latency Garbage Collector (Experimental)](https://openjdk.org/jeps/333)
- [JEP 377: ZGC: A Scalable Low-Latency Garbage Collector (Production)](https://openjdk.org/jeps/377)
- [JEP 439: Generational ZGC](https://openjdk.org/jeps/439)
- [JEP 474: ZGC: Generational Mode by Default](https://openjdk.org/jeps/474)
- [JEP 490: ZGC: Remove the Non-Generational Mode](https://openjdk.org/jeps/490)
- [《深入理解 Java 虚拟机》第 3 版，第 3 章：垃圾收集器与内存分配策略](../../../jvm-book/content/06-第3章-垃圾收集器与内存分配策略.md)
