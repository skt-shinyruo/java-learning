# Java Object Padding（对象对齐填充）

本文整理“**Java 对象对齐填充（alignment padding）**”相关概念，并解释：

- 什么是对象 padding（内部 padding / 尾部 padding）
- 为什么 HotSpot 常见默认是 **8 字节对齐**
- 为什么对齐值通常要求是 **2 的幂**
- 为什么需要 padding（性能、实现复杂度、并发原子语义等）
- `volatile` / CAS 等原子操作与对齐的关系（偏硬件与 HotSpot 实现视角）

> 备注：这里讨论的是 JVM/HotSpot 的**实现细节与常见行为**，不属于 Java 语言规范的“必须如此”。

> 前置阅读建议：[Java Object Layout（对象布局基础）](java-object-layout.md)（对象头、oop、数组头、字段 offset 等概念先对齐，再看 padding 更顺）。

---

## 1) 什么是对象对齐与 padding

在 HotSpot 中，一个对象在堆里通常由两部分组成：

- **对象头（header）**：例如 Mark Word、Klass 指针等（大小与 JVM 配置相关）。
- **实例字段（instance fields）**：你在类里声明的字段（以及父类字段）。

**对齐（alignment）**：某些数据类型/对象起始地址需要落在特定的字节边界（例如 8-byte 边界）。

**padding（填充字节）**：为了满足对齐要求，JVM 会在内存布局中插入一些“没有业务意义的空字节（holes）”。

常见两类 padding：

1. **字段之间的 padding（internal padding）**  
   为了让下一个字段（例如 `long/double`）从合适的边界开始，可能在前一个字段后插入空字节。
2. **对象末尾的 padding（tail / external padding）**  
   为了让**对象总大小**满足对象对齐要求（常见 8-byte），会在对象末尾补齐到对齐倍数。

> 注意：HotSpot 可能会做**字段重排**来减少 padding，因此“源码声明顺序”不一定等于“内存布局顺序”。要看真实布局，推荐用 JOL。

---

## 2) 为什么常见默认是 8 字节对齐

在 64-bit HotSpot 上，常见默认是：

- `-XX:ObjectAlignmentInBytes=8`

直觉上：**8 字节是 64-bit 机器的“自然粒度”**，很多实现都围绕它做优化。

主要原因：

1. **适配 64-bit 数据的自然对齐**  
   `long/double` 是 8 字节；对象头里也常含 64-bit 的 Mark Word。让它们落在 8-byte 边界上，硬件读写通常更高效、更简单。

2. **控制内存浪费**  
   如果对象大小向上补齐到 8 的倍数，尾部 padding 最多浪费 **0–7 字节/对象**。  
   对齐更大（例如 16）会带来更高的潜在浪费（0–15 字节/对象）。

3. **配合 Compressed Oops（压缩指针）**  
   8-byte 对齐意味着对象地址的低 3 bit 恒为 0。HotSpot 可以把引用按“索引”存成 32-bit（narrow oop），用位移恢复地址（例如 `<< 3`）。  
   这样可以显著节省堆空间、提升缓存命中。

> 对齐并不是固定为 8：你可以用 `-XX:ObjectAlignmentInBytes=16` 等调整（但只能取 2 的幂，并且有范围限制）。

### 2.1 oop（ordinary object pointer）是什么：它和“对象本体”不是一回事

在 HotSpot 术语里：

- **oop（ordinary object pointer）**就是 JVM 对“**普通 Java 对象引用**”的称呼。
- oop 本身不是“对象头里的某个字段结构体”，而是一段 bits（值），用来定位到堆中某个对象的**起始地址（对象头处）**。

把“引用值”和“被引用对象”分开画，会更直观（这里先假设 `UseCompressedClassPointers=on`）：

```text
------------------ 对象 A（在堆上） ------------------+
| 0..7    Mark Word (8B)                              |
| 8..11   Klass Pointer (4B, compressed klass)        |
| 12..15  ref: oop（引用槽位，指向对象 B 的起始地址）     |
| ...     其他字段 / 可能的 padding                     |
+-----------------------------------------------------+
                         |
                         v  （ref 解码成地址后）
+----------------- 对象 B（在堆上） -------------------+
| 0..7    Mark Word (8B)                              |
| 8..11   Klass Pointer (4B, compressed klass)        |
| 12..    实例字段...                                  |
+-----------------------------------------------------+
```

因此当你讨论 “oop 大小是 4B 还是 8B” 时，本质是在讨论：**对象字段/数组元素里那个“引用槽位”的存储宽度**，而不是在讨论对象头内部有什么变化。

### 2.2 对象头 12B 与 `klass gap`：为什么 4B 会“吃洞”，8B 会“留洞”

在 64-bit HotSpot 上，如果开启 `UseCompressedClassPointers`，对象头常见是：

- `Mark Word`：8B
- `Klass Pointer`：4B（压缩类指针）
- 合计：12B

于是实例字段的起始偏移就是 **12**。这会留下一个很关键的 4B 区间（`12..15`），经常被称为 `klass gap`：

```text
UseCompressedClassPointers=on（典型）
0..7    mark (8B)
8..11   klass (4B)
12..15  klass gap（可被 4B 字段/4B 引用复用；否则表现成 padding）
16..    继续放字段（需要 8B 对齐的字段通常从 16 开始）
```

如果关闭 `UseCompressedClassPointers`，对象头更常见变成 16B（8B mark + 8B klass），字段直接从 16 开始，就不存在这块“可复用的洞位”了。

数组对象是个特例：通常会把 `length` 放进这 4B（`12..15`），所以数组头在该配置下常见从 **16B** 起步。

### 2.3 CompressedOops 压缩的是什么：把“引用槽位”从 8B 变成 4B

`UseCompressedOops` 影响的是：**堆里保存引用的位置**（对象的引用类型字段、`Object[]` 的元素等）——也就是 oop 的落点。

- 开：引用槽位通常用 **4B 的 `narrowOop`** 存
- 关：引用槽位用 **8B 的 64-bit 指针**存

`narrowOop` 不是完整地址，而是按对象对齐缩放后的值；读取时再恢复为 64-bit 地址：

```text
addr = (narrow << shift) + base
```

其中 `shift` 常见来自对象对齐（8B 对齐 → `shift=3`）。这也是为什么“对象对齐（ObjectAlignmentInBytes）”和“压缩 oop 能否成立/能省多少”紧密相关。

### 2.4 对齐 + 压缩指针如何共同改变 padding（几个典型大小对比）

下面用一些“最小例子”把 `klass gap`、internal padding、tail padding 串起来。假设：

- 64-bit HotSpot
- `ObjectAlignmentInBytes=8`
- `UseCompressedClassPointers=on`（头 12B，存在 `klass gap`）

**例 1：只有一个 `int`**

```java
class OneInt { int x; }
```

- `x` 是 4B，可以直接放在 `12..15`，把 `klass gap` 吃掉 → **对象大小常见 16B**（12B 头 + 4B 字段，已经对齐到 8）

**例 2：只有一个 `long`**

```java
class OneLong { long x; }
```

- `long` 需要 8B 对齐，起始从 12 对齐到 16 → `12..15` 变成 **4B internal padding**
- `x` 放在 `16..23` → **对象大小常见 24B**

**例 3：只有一个对象引用**

```java
class OneRef { Object r; }
```

- `UseCompressedOops=on`：`r` 是 4B narrow oop，可放在 `12..15` → **对象大小常见 16B**
- `UseCompressedOops=off`：`r` 变成 8B 指针，通常要从 16 开始 → `12..15` 变成 **4B internal padding**，整体变为 **24B**

**例 4：`Object[]`（引用数组）**

- 数组头在该配置下常见是 **16B**（mark 8 + klass 4 + length 4），元素区从 16 开始
- 元素是 oop：
  - `UseCompressedOops=on`：每个元素 4B
  - `UseCompressedOops=off`：每个元素 8B

因此 `new Object[N]` 的体积会非常敏感：**压缩 oop 关闭后，元素区几乎直接按比例翻倍**，再叠加对象对齐带来的尾部补齐。

---

## 3) 为什么对齐值通常要求是 2 的幂（而不是 7）

根本原因：**二进制机器上，2 的幂对齐可以用位运算快速完成“检查/取整”**。

假设对齐值 `A = 2^k`：

- 判断 `addr` 是否对齐：`(addr & (A - 1)) == 0`  
  因为 `A-1` 的二进制刚好是低 `k` 位全 1（掩码），`addr & (A-1)` 就是在取“最低 k 位”，也就是余数。
- 向上取整到对齐边界：`(addr + (A - 1)) & ~(A - 1)`

例如对齐到 8（`A=8`，`A-1=7`）：

- 是否 8 对齐：`(addr & 7) == 0`
- 向上补齐：`(x + 7) & ~7`

如果对齐值不是 2 的幂（例如 7）：

- “是否对齐”就变成 `addr % 7 == 0`（需要除法/取模）
- “向上补齐”也要更复杂的计算

而分配器、GC、JIT 在对象布局/分配/扫描时会非常频繁地做这些操作，因此 JVM 会强烈偏好 2 的幂对齐。  
HotSpot 也会直接限制 `ObjectAlignmentInBytes` 必须是 2 的幂，并且在一个允许范围内（常见为 8..256）。

---

## 4) 为什么需要 padding（不补不行吗？）

从“实现与性能”角度，padding 的价值在于：

1. **让读写更高效、更可预测**  
   对齐后的 64-bit 读写更可能是一条自然对齐的 load/store；不对齐可能导致额外的硬件处理（甚至跨边界拆分读写）。

2. **让对象分配与 GC 扫描更简单**  
   HotSpot 往往让对象大小按对齐倍数补齐，这样“对象挨着对象放”时，下一对象天然也对齐；GC 在扫描对象边界/字段时实现更简单。

3. **让并发原语更容易直接映射到硬件指令**  
   这是 `volatile` / CAS / 锁实现里非常重要的一点（下一节展开）。

---

## 5) 对齐与 `volatile` / CAS / 锁：为什么说“更容易实现”

### 5.1 先区分两件事：原子性 vs 可见性/有序性

- **原子性（atomicity）**：一次读/写不能“撕裂”（word tearing）。例如读 `volatile long` 不能读到“高 32 位新、低 32 位旧”的拼接值。
- **可见性/有序性（visibility/ordering）**：`volatile` 还要求 happens-before 语义，通常通过内存屏障实现。

对齐主要影响的是：**能否用简单、标准的硬件指令序列保证原子性**；然后 JVM 再叠加屏障满足可见性/有序性。

### 5.2 不对齐会带来的典型麻烦：word tearing

如果某个 8 字节值（`long/double`）落在不合适的地址上：

- CPU/JIT 可能不得不拆成两次 4 字节访问，再拼起来
- 这时并发读写就可能出现“撕裂”：读到半旧半新的值

Java 内存模型要求 `volatile long/double` 的读写具备足够强的语义，HotSpot 需要避免走到“拆分访问导致撕裂”的实现路径。

> 补充：Java 语言层面的基本类型里，单个变量最大就是 8 字节（`long` / `double`）。所谓“超过 8 字节”的数据通常是对象/数组，或由多个字段拼成的“逻辑值”。这类复合状态并没有“整体原子读写/CAS”的通用保证：如果你需要把它当成一个整体原子发布/更新，通常要么用锁，要么用 `AtomicReference` 指向不可变对象（CAS 更新引用）来实现。

### 5.3 CAS/原子 RMW 与缓存行：跨边界会很痛

很多原子指令（CAS、fetch-add 等）属于 **RMW（read-modify-write）**。在现代 CPU 上，它们通常依赖缓存一致性协议（例如 MESI）：

- CPU 需要先把目标内存所在的**缓存行（cache line，常见 64B）**拿到“独占/可修改”权限
- 在本核完成原子更新

如果一个 8 字节值**跨了两个缓存行**，硬件实现会更复杂、代价更大，某些场景甚至不支持“跨行原子”。  
而 8-byte 对齐能确保 8 字节数据不会从“缓存行末尾附近”跨过去（因为缓存行边界也是 2 的幂，且是 8 的倍数）。

结论：**对齐能让 JVM 更放心地用硬件 CAS/原子指令来实现 `AtomicLong`、锁的状态更新等**。

> 延伸：部分 CPU 也支持 16-byte 原子指令（通常要求 16-byte 对齐且不跨 cache line）。但 Java 标准库/JMM 并不为“>64-bit 的原子变量”提供同级别的通用保证，因此对 Java 代码而言，更常见的仍是“锁”或“引用级 CAS（如 `AtomicReference`）”。

### 5.4 HotSpot 的做法：用布局与 padding 提前把路铺好

HotSpot 通过对象布局与 padding，尽量保证：

- `long/double` 字段落在合适的对齐位置（常见 8-byte）
- 对象总大小补齐到 8 的倍数，让下一个对象起始也对齐

这样 JIT 才更容易把：

- `volatile long` 读/写：编译成对齐的 64-bit load/store + 必要屏障
- `AtomicLong.compareAndSet`：编译成对齐地址上的硬件 CAS 循环
- 锁（Mark Word/Klass 指针相关更新）：编译成对齐地址上的原子操作

> 总结一句：padding 不直接“提供 volatile 语义”，但它让 JVM 更容易用**更少、更标准的硬件原子指令**去满足 Java 并发语义的底座要求。

---

## 6) 怎么验证：看你机器上的真实布局与对齐参数

### 6.1 看 JVM 的对象对齐参数

```bash
java -XX:+PrintFlagsFinal -version | rg ObjectAlignmentInBytes
```

更推荐一次把“对象对齐 + 压缩指针相关开关”一起看（它们决定了你在 JOL 里常见的 `12/16` 等 offset）：

```bash
java -XX:+PrintFlagsFinal -version | rg "UseCompressedOops|UseCompressedClassPointers|ObjectAlignmentInBytes"
```

下面用一段真实输出做“读法示例”（不同 JDK/平台细节可能略有差异，但这 3 个 flag 的含义一致）：

```text
intx ObjectAlignmentInBytes                   = 8
bool UseCompressedClassPointers               = true
bool UseCompressedOops                        = true
OpenJDK 64-Bit Server VM
```

> 补充：`UseCompressedOops/UseCompressedClassPointers` 通常是 **ergonomic**（由 JVM 按堆大小/平台自动决定是否开启），不要预设它“一定为 true”，以实际输出为准。

如何解读：

- `OpenJDK 64-Bit Server VM`（以及 flag 行里常见的 `lp64_product`）说明：**这是一个 64-bit JVM 进程**，在 native 世界里“指针/机器字”的宽度通常是 **64-bit（=8B）**。  
  但这不等于“对象里所有字段都是 8B”：HotSpot 可以把对象引用/klass 指针用 4B 的压缩形式存起来（见下两条）。
- `ObjectAlignmentInBytes = 8`：说明 **Java 堆里对象按 8B 对齐**。对象地址的低 3 bit 恒为 0（这是压缩指针能成立的重要前提之一），也会影响 `LogMinObjAlignmentInBytes`（默认 3，对应 `<< 3`）。
- `UseCompressedClassPointers = true`：对象头里的 klass 指针以 **4B（narrowKlass）** 存储；配合 **8B mark word**，对象头常见会变成 **12B**，于是 `12..15` 这 4B 被称为 **klass gap**：  
  - 可以被某些 4B 字段直接复用（例如 `int` 放在 offset 12）  
  - 也可能因为字段对齐要求更强而表现成 internal padding（例如 `long/double` 往往要从 offset 16 开始）
- `UseCompressedOops = true`：对象里的引用字段以 **4B（narrow oop）** 存储；解码时会用 `heap_base + (narrow << shift)` 还原成 64-bit 地址，其中 `shift` 往往就是 `LogMinObjAlignmentInBytes`（对齐为 8 时通常是 3）。

如果你想把 `heap base` / `shift` 直接打印出来（便于把 `<< 3` 这件事坐实），可以跑：

```bash
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintCompressedOopsMode -version
```

你也可以尝试修改对齐（HotSpot 会限制必须是 2 的幂且在允许范围内）：

```bash
java -XX:ObjectAlignmentInBytes=16 -version
```

### 6.2 看对象真实布局：JOL（Java Object Layout）

JOL 能打印字段 offset、对象头、padding、对象总大小等信息。常见用法是：

- 依赖：`org.openjdk.jol:jol-core`
- 输出：`org.openjdk.jol.info.ClassLayout.parseClass(Foo.class).toPrintable()`

由于 HotSpot 可能会字段重排，**不要只靠“手算”推断布局**；以 JOL 输出为准。

### 6.3 本仓库的示例代码：用 JOL 直观看到 padding

仓库里提供了测试用例：`yier.bubu.jvm.ObjectPaddingJolTest`（每个 test 方法都是一个小案例，使用 JOL 打印多个 class/array 的布局）。

```bash
# 跑 jvm 模块的全部测试（包含本示例）
mvn -pl jvm -am test

# 只跑本测试类
mvn -pl jvm -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ObjectPaddingJolTest test

# 只跑其中一个小案例（按需替换方法名）
mvn -pl jvm -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ObjectPaddingJolTest#tailPadding_singleByteField_shouldHaveExternalLosses test
```

---

## 7) 额外补充：对象 padding vs 缓存行 padding（false sharing）

本文讲的是对象/字段为了“对齐”产生的 padding。并发里还常见另一类“人为 padding”：

- 为了避免 **false sharing**，把热点字段隔开到不同 cache line
- HotSpot 提供了 `@sun.misc.Contended`（或 `jdk.internal.vm.annotation.Contended`）等机制（需要 JVM 参数开启）

它解决的是“不同线程频繁写相邻字段导致缓存行抖动”的问题，和“对象起始/字段对齐”的 padding 相关但不完全是一回事。

---

## 8) HotSpot 源码里 padding 的落点（以 OpenJDK 21 为例）

如果你把 HotSpot 里所有“padding/对齐”相关代码都摊开看，会觉得散：有的在启动参数、有的在 klass、有的在字段布局、有的在数组 oop 描述符里。  
它们其实都在做同一件事：**把“某个 offset/size”向上取整到某个对齐边界（round-up）**。只要先把“边界是谁、单位是什么”搞清楚，就不会迷路。

### 8.1 先建立一张“落点地图”：3 类 padding + 1 个特殊坑位

HotSpot 里常见的 padding 可以按“来源”分成三类：

1. **tail padding（对象末尾补齐）**：对象总大小向上取整到对象对齐（默认 8B）。  
   关键函数：`align_object_size()`（`src/hotspot/share/utilities/align.hpp`）
2. **internal padding（字段之间插空）**：字段 offset 向上取整到字段自身的对齐要求（`long/double` 常见 8B）。  
   关键位置：`FieldLayoutBuilder`（`src/hotspot/share/classfile/fieldLayoutBuilder.cpp`）里各种 `align_up(...)`
3. **@Contended padding（人为隔离）**：不是“为了对齐”，而是“为了隔开缓存行”，同样在 `FieldLayoutBuilder` 里插入 `PADDING` block。

再加一个你经常会遇到的“固定洞位”：

4. **klass gap（头部 12B 留出的 4B 洞）**：`UseCompressedClassPointers` 开时，头部从 16B 变 12B，offset `12..15` 这 4B 既可能被字段/数组 length 复用，也可能表现成 padding。  
   关键位置：`instanceOopDesc::base_offset_in_bytes()`（`src/hotspot/share/oops/instanceOop.hpp`）和 `arrayOopDesc::length_offset_in_bytes()`（`src/hotspot/share/oops/arrayOop.hpp`）

> 记住一句：**对齐 = round-up；padding = round-up 产生的空洞**。源码看起来分散，是因为 round-up 分布在“启动 → 类加载 → 对象/数组大小计算”这三条路径里。

### 8.2 启动期：`ObjectAlignmentInBytes` 变成全局常量（bytes/words 两套）

`ObjectAlignmentInBytes` 是 flag（字节单位），HotSpot 运行时真正反复用的是换算后的全局变量：

- `MinObjAlignmentInBytes`：字节单位的对象对齐
- `MinObjAlignment`：HeapWord 单位的对象对齐（`MinObjAlignmentInBytes / HeapWordSize`）
- `LogMinObjAlignmentInBytes`：`log2`（给压缩指针编码/位移用）

它们在 VM 初始化时由 `set_object_alignment()` 计算（`src/hotspot/share/runtime/arguments.cpp`）：

```cpp
void set_object_alignment() {
  assert(is_power_of_2(ObjectAlignmentInBytes), "ObjectAlignmentInBytes must be power of 2");
  MinObjAlignmentInBytes     = ObjectAlignmentInBytes;
  MinObjAlignment            = MinObjAlignmentInBytes / HeapWordSize;
  MinObjAlignmentInBytesMask = MinObjAlignmentInBytes - 1;
  LogMinObjAlignmentInBytes  = exact_log2(ObjectAlignmentInBytes);
  LogMinObjAlignment         = LogMinObjAlignmentInBytes - LogHeapWordSize;
  OopEncodingHeapMax = (uint64_t(max_juint) + 1) << LogMinObjAlignmentInBytes;
}
```

这里的 `LogMinObjAlignmentInBytes`（默认 3）就是你常见的 “compressed oop 解码 `<< 3`” 的来源。

### 8.3 `align_object_size()`：tail padding 的通用入口（但别被“word 单位”绕晕）

HotSpot 里对象大小很多时候以 **HeapWord 为单位**（而不是字节）流转，所以 `align_object_size()` 的参数也是 word 数（`src/hotspot/share/utilities/align.hpp`）：

```cpp
// Align objects in the Java Heap by rounding up their size, in HeapWord units.
template <typename T>
inline T align_object_size(T word_size) {
  return align_up(word_size, MinObjAlignment);
}
```

一个容易产生错觉的点是：

- 64-bit 下默认 `ObjectAlignmentInBytes=8`，而 `HeapWordSize` 往往也是 8；于是 `MinObjAlignment == 1`，`align_object_size()` 看起来“什么都没做”。
- 但它依然是统一入口：一旦你把对齐调大（例如 16/32），这里立刻会引入额外的 tail padding。

### 8.4 类加载期：`FieldLayoutBuilder` 同时决定 internal padding 和 tail padding

类的“实例字段布局”是在 class loading/linking 期间算出来并固化到 klass 里的。核心流程是：

1. 先确定实例字段的起点 offset（头部大小 + 是否有 klass gap）
2. 按策略放字段（排序/填洞），每放一个字段都要 `align_up` 到该字段对齐 → internal padding 就在这里出现
3. 计算实例数据结束位置 `instance_end`（bytes）
4. 先把 `instance_end` 向上补齐到 `HeapWordSize`（因为很多 size 用 word 走）
5. 再把 word 数用 `align_object_size()` 向上补齐到 `MinObjAlignment` → 这一步对应“对象对齐”层面的 tail padding

JDK 21 的 `FieldLayoutBuilder` 里能看到类似收尾（`src/hotspot/share/classfile/fieldLayoutBuilder.cpp`）：

```cpp
int instance_end = align_up(_layout->last_block()->offset(), wordSize);
_info->_instance_size = align_object_size(instance_end / wordSize);
```

另外，`@Contended` 相关的“人为 padding”（避免 false sharing）也是在这里插入 `PADDING` block（例如 `insert_contended_padding()`），对应第 7 节提到的那条线。

### 8.5 头部 12/16、klass gap 与数组 length：把你在 JOL 里看到的数字对上源码

当开启 `UseCompressedClassPointers` 时，对象头常见是 12B（8B mark + 4B klass），于是实例字段起始偏移变成 12（`src/hotspot/share/oops/instanceOop.hpp`）：

```cpp
static int base_offset_in_bytes() {
  return (UseCompressedClassPointers) ?
          klass_gap_offset_in_bytes() :
          sizeof(instanceOopDesc);
}
```

因此：

- `int` 这种 4B 字段可以直接塞进 offset 12（“吃掉”klass gap）
- `long/double` 需要 8B 对齐，会把起点从 12 推到 16，于是你看到 4B 的 internal padding

数组对象多一个 `length` 字段；`UseCompressedClassPointers` 开时，HotSpot 把 `length` 放在 klass gap 那 4B（`src/hotspot/share/oops/arrayOop.hpp`）：

```cpp
static int length_offset_in_bytes() {
  return UseCompressedClassPointers ? klass_gap_offset_in_bytes()
                                    : sizeof(arrayOopDesc);
}
```

数组的 “header size / base offset / 元素区对齐” 是另一条布局逻辑；但最后得到的**总大小**仍然会走 “round-up 到 HeapWord / round-up 到 MinObjAlignment” 的路径，所以尾部补齐依然存在。

### 8.6 如果你想自己在 OpenJDK 源码里追一遍（最短路径）

- 先看 `src/hotspot/share/runtime/arguments.cpp` 的 `set_object_alignment()`：把 `ObjectAlignmentInBytes` 和 `MinObjAlignment*` 对上号
- 再全局搜 `align_object_size(`：看看“对象/数组 size”在哪些路径被统一 round-up
- 最后看 `src/hotspot/share/classfile/fieldLayoutBuilder.cpp`：internal padding / 填洞 / `@Contended` 的插入都在这里
