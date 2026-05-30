# Java Object Layout（对象布局基础）

本文先把 “Java 对象在 HotSpot 里长什么样” 讲清楚，再去理解对象对齐与 padding 会更顺。

内容主要回答：

- Java 里的“对象”和“引用”分别是什么（以及 HotSpot 的 oop 术语）
- 一个对象在堆里通常由哪些部分组成（对象头 / 实例数据 / padding）
- 对象头里的 Mark Word / Klass Pointer 分别用来干什么
- 数组对象的布局（数组头、length、元素区）
- CompressedOops / CompressedClassPointers 如何改变引用宽度与对象头大小
- 如何估算对象大小，以及用 JOL / Instrumentation 获取实际大小

> 备注：本文讨论的是 HotSpot/OpenJDK 的常见实现。对象头大小、字段布局、对齐值会受 **JDK 版本 / GC / JVM 参数**
> 影响；不要把这里的数字当成“语言规范保证”，以 JOL 实测为准。

---

## 1. 对象 vs 引用（reference / oop）

Java 语言层面你写的：

```java
Foo x = new Foo();
```

可以拆成两件事：

- **对象（object）**：`new Foo()` 创建出来的那块堆内存（包含对象头和字段数据）。
- **引用（reference）**：变量 `x` 保存的“指向该对象”的值。

在 HotSpot 术语里，通常把“指向普通堆对象的引用”称为：

- **oop（ordinary object pointer）**

重要点：

- oop 本质是一个“定位到对象起始地址（对象头处）”的值，并不是一个自带字段的结构体。
- Java 语义上引用是抽象的；实现上 HotSpot 会把它落地为指针（或压缩后的指针编码），并由 GC 负责在对象搬迁后更新。

---

## 2. 普通对象在堆里的基本形状：header + instance + padding

绝大多数“普通对象”（非数组）在堆中的布局都可以抽象为：

```text
+----------------------+
| Object Header        |  对象头：Mark Word + Klass Pointer
+----------------------+
| Instance Fields      |  实例字段（含父类字段；可能发生重排/填洞）
+----------------------+
| (Tail Padding)       |  尾部补齐：对象总大小向上对齐到 ObjectAlignmentInBytes
+----------------------+
```

### 2.1 一张全景图：引用（oop）指向对象；对象头再指向类元数据

很多困惑来自于把“对象实例”和“类/方法”混在一起。HotSpot 的关系更像下面这张图：

![Java 引用、对象实例、类元数据和 JIT 代码之间的关系](images/java-object-layout-overview.svg)

你可以把它记成一句话：

- **对象实例只存“状态”（字段）**；**行为（方法/字节码）属于类**，被所有实例共享，所以放在类元数据里只存一份。

### 2.2 典型 64-bit HotSpot 下，“Mark(8B)+Klass(4/8B)+字段+padding” 的字节布局示意

下面用你在 JOL 里最常见的一种配置画图（64-bit，`UseCompressedClassPointers=on`）：

- 对象头：`Mark Word(8B) + Klass Pointer(4B)` → **12B**
- 对齐：默认 `ObjectAlignmentInBytes=8` → 对象总大小是 8 的倍数

**(1) 空对象：只有头部 + 尾部补齐**

```text
offset:  0        8        12       16
         +--------+--------+--------+
         |  mark  | klass  |  pad   |
         |  8B    |  4B    |  4B    |  => instanceSize = 16B
         +--------+--------+--------+
```

**(2) 一个 `int` 字段：正好把 12..15 的 4B “吃掉”**

```text
offset:  0        8        12       16
         +--------+--------+--------+
         |  mark  | klass  |  int   |
         |  8B    |  4B    |  4B    |  => instanceSize = 16B
         +--------+--------+--------+
```

**(3) 一个 `long` 字段：需要 8B 对齐，12..15 变成 internal padding**

```text
offset:  0        8        12       16       24
         +--------+--------+--------+--------+
         |  mark  | klass  |  pad   |  long  |
         |  8B    |  4B    |  4B    |  8B    |  => instanceSize = 24B
         +--------+--------+--------+--------+
```

如果 `UseCompressedClassPointers=off`，对象头更常见会变成 16B（8B mark + 8B klass），图里 `klass` 这一格会从 4B 变 8B，字段起始一般从 16 开始，“klass gap” 也就不存在了。

### 2.3 tail padding 是怎么产生的：把对象大小 round-up 到对齐倍数

HotSpot 通常会把“对象总大小”向上取整到 `ObjectAlignmentInBytes` 的倍数：

```text
alignedSize = align_up(rawSize, ObjectAlignmentInBytes)
tailPadding = alignedSize - rawSize   // 取值范围 0..(alignment-1)
```

其中 `rawSize` 可以粗略理解为 “对象头 + 实例字段 +（内部对齐导致的空洞）” 的总和。  
因为默认对齐常见是 8，所以 **tail padding 最多浪费 0..7 字节/对象**。

### 2.4 为什么方法/字节码不在对象里，而在类元数据里

从“空间与共享”的角度，方法/字节码放在对象里会非常不划算：

- 一个类可能创建成千上万个实例；如果每个实例都带一份方法字节码，内存会爆炸。
- 方法是“类级别的行为”，对所有实例共享；实例只需要携带能定位到类的入口（Klass Pointer）即可。

因此 HotSpot 的分工通常是：

- **对象实例（Heap）**：对象头 + 字段数据（状态）
- **类元数据（Metaspace）**：字段 offset 信息、常量池、方法字节码（以及分派表等）
- **JIT 编译结果（CodeCache）**：热点方法编译后的机器码

所以你看到的 “Mark Word + Klass Pointer + 实例字段 + padding” 就是对象实例需要携带的最小集合：既能支持并发/GC/锁（Mark Word），又能支持类型/分派/GC 扫描（Klass Pointer），再加上真正的业务状态（字段）。

几个常见误解顺便澄清：

- **对象里只有“状态”（字段数据）**，方法/字节码不在对象里（方法元数据在 klass/元空间里）。
- Java 对象的“地址”不是语言层面的稳定可见概念：多数 GC 会移动对象，地址会变，但引用会被更新，所以你感觉不到。

---

## 3. 对象头之一：Mark Word（身份/锁/GC 的复用槽）

在 64-bit HotSpot 上，Mark Word 常见为 **8 字节**。它是一个“复用槽”，不同状态下含义不同（不是固定格式一直不变）。

你可以把它理解成：JVM 为了避免每个对象都额外挂很多元数据字段，把一些“和对象绑定的运行时信息”塞进了这个 8B 里。

常见用途（概念级）：

- **锁状态**：`synchronized` 相关（无锁 / 轻量级锁 / 重量级锁 等）。一些锁形态会让 Mark Word 临时指向栈上的锁记录或监视器结构。
- **identity hash**：`System.identityHashCode(obj)` 可能会把 hash 写进 Mark Word（或触发对象膨胀/搬运到别处存）。
- **对象年龄（age）**：分代 GC 里对象晋升相关的年龄计数。
- **GC 标记/转发表达**：某些 GC/阶段可能复用部分位表达“已标记”“转发指针”等信息。

因此你在 JOL/hsdis 里看到的 Mark Word，必须结合当时的运行状态解释；同一个对象在不同时间点也可能不同。

---

## 4. 对象头之二：Klass Pointer（类元数据入口）

每个对象都需要知道“自己是什么类型”，以支持：

- 虚方法分派（调用哪个实现）
- `instanceof` / 强转类型检查
- 反射/`getClass()`
- GC 扫描：对象里哪些 offset 是引用字段（oop），需要当成指针跟踪

HotSpot 做法是：在对象头里放一个指向类元数据的指针，常被称为 **Klass Pointer**（指向 HotSpot 的 `Klass` 结构）。

在 64-bit HotSpot 上：

- 若开启 `UseCompressedClassPointers`：klass 指针常以 **4B 的 narrowKlass** 形式存放
- 若关闭：klass 指针常为 **8B**

这会显著影响对象头大小：

- **12B 头（8B mark + 4B klass）**：常见于 `UseCompressedClassPointers=on`
- **16B 头（8B mark + 8B klass）**：常见于 `UseCompressedClassPointers=off`

当头部是 12B 时，`12..15` 这 4B 区间经常被称为 **klass gap**：它可能被 4B 字段/4B 引用复用，也可能表现为 padding。

---

## 5. 实例字段怎么存：offset、重排、对齐与“填洞”

字段在对象里不是“按源码顺序线性排下去”这么简单，原因包括：

- `long/double` 等类型有更强的对齐需求（常见 8-byte）
- JVM 会尝试通过 **字段重排/填洞（field packing）** 减少内部空洞（internal padding）
- 父类字段与子类字段要合并成一个连续布局（以及对齐）

所以你更可靠的心智模型是：

- 类加载/链接时，HotSpot 会计算出“每个字段的 offset”
- 运行时读写字段，就是对 `oop + offset` 做寻址

一个典型现象（64-bit + 压缩类指针时更明显）：

- 头部是 12B → 如果你有一个 `int` 或一个 **4B 的引用字段**，很可能正好塞进 `12..15`，把洞“吃掉”
- 如果第一个字段是 `long/double`（需要从 16 开始）或 **8B 引用（关闭压缩 oop 时）**，`12..15` 就会变成 internal padding

---

## 6. 数组对象：数组头（含 length）+ 元素区

数组也是对象，所以它也有对象头；此外它还必须保存长度：

```text
+----------------------+
| Mark Word (8B)       |
| Klass Pointer (4/8B) |
| length (4B)          |
+----------------------+
| Elements...          |
+----------------------+
| (Tail Padding)       |
+----------------------+
```

关键点：

- `length` 是一个 `int`（4B）。
- **基本类型数组**（如 `int[]`）的元素区存的是原始值（4B/8B 等），没有“每个元素一个对象头”这种开销。
- **引用数组**（如 `Object[]`）的元素区存的是 oop（引用槽位），每个元素是 4B（CompressedOops 开）或 8B（关闭）。

因此当你分析 `Object[]`、`ArrayList`、`HashMap.Node[]` 等结构时，CompressedOops 对内存占用影响会非常直观。

---

## 7. CompressedOops / CompressedClassPointers：压缩的到底是什么

压缩的对象指针通常有两类（名字很像，但压缩目标不同）：

1. **CompressedOops（`UseCompressedOops`）**  
   压缩的是：对象字段/引用数组元素等位置保存的 **oop（普通对象引用）**，通常从 8B 压到 4B（`narrowOop`）。

2. **CompressedClassPointers（`UseCompressedClassPointers`）**  
   压缩的是：对象头里保存的 **klass 指针**，通常从 8B 压到 4B（`narrowKlass`）。

两者经常同时出现（尤其是“12B 对象头 + 4B 引用槽位”的典型组合），并且都和 **对象对齐（ObjectAlignmentInBytes）** 有强关联（对齐让地址低位可被“省掉”，从而可编码为 32 位值）。

---

## 8. 怎么在你的 JVM 上把“真实布局”打印出来（JOL）

建议把以下两类信息一起看：

### 8.1 打印 JVM 最终采用的参数

```bash
java -XX:+PrintFlagsFinal -version | rg "UseCompressedOops|UseCompressedClassPointers|ObjectAlignmentInBytes"
```

如果 JVM 支持该诊断参数，也可以直接打印压缩 oop 的 base/shift（更直观理解 “<<3”）：

```bash
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintCompressedOopsMode -version
```

### 8.2 运行本仓库的 JOL 测试用例

本模块的 JOL 示例测试：

- `yier.bubu.jvm.ObjectPaddingJolTest`：偏 padding 案例
- `yier.bubu.jvm.JavaObjectLayoutJolTest`：偏对象/数组基础布局

运行方式：

```bash
mvn -pl jvm -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=JavaObjectLayoutJolTest test
```

---

## 9. 对象大小速算与获取方式

讨论对象大小时，先区分两个口径：

- **shallow size**：只算对象自身占用，包括对象头、实例字段和 padding；不递归计算引用指向的其他对象。
- **deep / retained size**：把对象引用出去的对象图也算进去，通常需要堆分析工具或 JOL `GraphLayout` 这类对象图工具辅助判断。

大多数“一个 Java 对象有多大”的手算，默认算的是 **shallow size**。

### 9.1 手算 shallow size

以常见的 64-bit HotSpot、开启 `UseCompressedOops` / `UseCompressedClassPointers`、`ObjectAlignmentInBytes=8` 为例：

| 内容 | 常见大小 |
| --- | ---: |
| 普通对象头 | 12B（8B Mark Word + 4B Klass Pointer） |
| 数组对象头 | 16B（对象头 + 4B length） |
| `boolean` / `byte` | 1B |
| `char` / `short` | 2B |
| `int` / `float` | 4B |
| `long` / `double` | 8B |
| 对象引用字段 / `Object[]` 元素 | 4B（压缩 oop 开启时） |
| 对象引用字段 / `Object[]` 元素 | 8B（压缩 oop 关闭时） |

普通对象可以按下面的思路估算：

```text
rawSize = objectHeaderSize + fieldsSize + internalPadding
objectSize = align_up(rawSize, ObjectAlignmentInBytes)
```

数组对象可以按下面的思路估算：

```text
rawSize = arrayHeaderSize + elementSize * length
arraySize = align_up(rawSize, ObjectAlignmentInBytes)
```

几个典型例子：

```text
new Object()
= 12B header + 4B tail padding
= 16B

class OneInt { int x; }
= 12B header + 4B int
= 16B

class OneLong { long x; }
= 12B header + 4B internal padding + 8B long
= 24B

new int[3]
= 16B array header + 3 * 4B int + 4B tail padding
= 32B
```

手算只能做估算，因为 HotSpot 可能重排字段、复用对象头后的空洞，也可能因为 JVM 参数或 JDK 版本改变对象头和引用宽度。

### 9.2 用 JOL 获取布局与大小

JOL 是本仓库里最推荐的验证方式。查看单个对象的 shallow layout：

```java
System.out.println(ClassLayout.parseInstance(obj).toPrintable());
```

如果要看一个对象图的大致占用，可以使用 JOL 的 `GraphLayout`：

```java
System.out.println(GraphLayout.parseInstance(obj).toFootprint());
```

`ClassLayout` 适合解释对象头、字段 offset、padding 和实例大小；`GraphLayout` 更适合观察“这个对象连着引用出去的一组对象”大约占多少空间。

### 9.3 用 Instrumentation 获取 shallow size

JDK 还提供了 `java.lang.instrument.Instrumentation#getObjectSize(Object)`：

```java
long bytes = instrumentation.getObjectSize(obj);
```

它的特点是：

- 返回的是对象自身的 **shallow size**，不递归包含引用对象。
- 需要通过 Java Agent 拿到 `Instrumentation` 实例，普通 `main` 方法里不能直接 new 出来。
- 返回值是 JVM 实现相关的近似结果，适合观测和排查，不适合作为业务逻辑里的稳定协议。

实践里通常优先用 JOL 理解布局；需要在 agent 或诊断工具里批量采样对象自身大小时，再考虑 `Instrumentation#getObjectSize()`。

---

## 10. 下一步：再去看 padding（内部/尾部）

当你对“对象头 + 字段 offset + 对齐”这三件事有感觉后，再读 padding 会顺很多：

- [Java Object Padding（对象对齐填充）](object-padding.md)
