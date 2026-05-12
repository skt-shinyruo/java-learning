# HotSpot 对象访问定位机制

本文解释 OpenJDK 默认虚拟机 HotSpot 如何从 Java reference 定位、访问堆上的对象。

结论先放前面：HotSpot 普通 Java 对象访问走的是 **直接指针** 体系，不是普通对象访问都经过句柄池。64 位 HotSpot 常见情况下会启用 **Compressed Oops**，把堆中的对象引用压缩成 32 位编码，使用时再解码成真实对象地址；这仍然属于直接指针体系，不是句柄访问。

> 备注：本文讨论的是 HotSpot/OpenJDK 的常见实现。Java 虚拟机规范只规定 reference 是指向对象的引用，并不规定虚拟机必须用句柄还是直接指针。

---

## 1. 整体链路

假设有如下代码：

```java
Person p = new Person();
int age = p.age;
Object friend = p.friend;
```

HotSpot 的访问路径可以抽象为：

```text
栈 / 寄存器中的 p
    -> Java 堆中的对象地址
        -> 对象头：mark word + klass/class 信息
        -> 实例字段区：age、friend 等字段
```

访问 `p.age` 时，不需要先查句柄表。HotSpot 在字段解析后已经知道 `age` 字段在 `Person` 对象内的偏移量，于是访问近似变成：

```text
读取地址 = object_address + field_offset
```

访问 `p.friend` 时，如果启用了压缩 oop，字段里存的可能是 32 位压缩引用，需要先解码成真实对象地址。

---

## 2. reference 在 HotSpot 中对应 oop

HotSpot 里，指向普通 Java 堆对象的引用通常称为：

```text
oop = ordinary object pointer
```

OpenJDK 源码中可以看到普通 `oop` 和压缩 `narrowOop` 的关系：

```cpp
typedef juint narrowOop;
typedef class oopDesc* oop;
```

也就是说：

- `oop` 是 `oopDesc*`，即直接指向对象描述结构的指针。
- `narrowOop` 是 32 位压缩引用编码。
- Java 语言层面的 reference 是抽象概念；HotSpot 实现中会落地为 oop 或可解码成 oop 的压缩值。

相关源码：

- OpenJDK `oopsHierarchy.hpp`: <https://github.com/openjdk/jdk/blob/master/src/hotspot/share/oops/oopsHierarchy.hpp>
- OpenJDK CompressedOops Wiki: <https://wiki.openjdk.org/display/HotSpot/CompressedOops>

---

## 3. Compressed Oops：压缩的是指针，不是句柄

64 位 JVM 如果每个对象引用都直接使用 64 位地址，对象字段和对象数组会明显膨胀。HotSpot 常见优化是启用 `UseCompressedOops`，把堆中的对象引用压缩成 32 位。

压缩 oop 的解码本质可以理解为：

```text
oop = base + (narrowOop << shift)
```

其中：

- `narrowOop`：堆中保存的 32 位压缩引用。
- `base`：Java 堆基址。
- `shift`：对象对齐带来的位移量。

这说明压缩 oop 仍然是“对象地址的编码形式”。它不是句柄地址，也不会先定位到一个句柄结构，再从句柄结构取对象实例地址。

启用压缩 oop 时，通常会被压缩的是：

- 对象实例字段中的引用。
- 对象数组中的引用元素。
- 对象头中的 klass/class 指针，前提是启用了 compressed class pointers。

解释器栈上的局部变量、操作数栈、方法参数和返回值通常不会保存压缩 oop；从堆加载引用时解码，写回堆时编码。JIT 编译后的代码更灵活，可能在寄存器或栈溢出槽里保存压缩或非压缩形式，取决于优化结果。

相关源码：

- OpenJDK `compressedOops.inline.hpp`: <https://github.com/openjdk/jdk/blob/master/src/hotspot/share/oops/compressedOops.inline.hpp>

---

## 4. 对象地址指向对象头

HotSpot 中 Java 对象的基础结构由 `oopDesc` 描述。经典对象头通常包含：

```text
mark word
klass pointer / compressed klass pointer
```

普通对象的常见布局可以抽象为：

```text
对象地址 A
+-------------------------+
| mark word               |  锁状态、GC 年龄、identity hash 等
+-------------------------+
| klass pointer           |  指向类型元数据，常为压缩 class pointer
+-------------------------+
| instance fields         |  实例字段
+-------------------------+
| padding                 |  对齐填充
+-------------------------+
```

数组对象会额外包含数组长度：

```text
数组对象地址 A
+-------------------------+
| mark word               |
+-------------------------+
| klass pointer           |
+-------------------------+
| array length            |
+-------------------------+
| array elements          |
+-------------------------+
```

`klass pointer` 指向 HotSpot 的 `Klass` / `InstanceKlass` 元数据。类元数据中包含字段布局、方法表、接口表、常量池等信息。也就是说，对象本身通过对象头保存了定位类型数据的入口。

相关源码：

- OpenJDK `oop.hpp`: <https://github.com/openjdk/jdk/blob/master/src/hotspot/share/oops/oop.hpp>

---

## 5. 字段访问：对象地址 + 字段偏移

字段访问的核心是：

```text
field_address = object_address + field_offset
```

例如：

```java
int age = p.age;
```

字节码是 `getfield`。字段引用第一次解析后，HotSpot 会知道 `age` 在 `Person` 对象中的偏移。解释器或 JIT 编译后的机器码就可以近似执行：

```text
addr = p + age_offset
value = *(int*)addr
```

如果访问的是引用字段：

```java
Object friend = p.friend;
```

则近似是：

```text
raw = *(p + friend_offset)
friend = decode_oop(raw)   // 如果启用了 compressed oops
```

如果没有启用压缩 oop，字段里保存的就是普通对象地址。

HotSpot 源码里有 `field_addr(int offset)`、`obj_field(int offset)` 这类接口，表达的就是“以当前对象地址为基准，通过字段偏移定位字段内存”。

---

## 6. 类型访问：通过对象头中的 klass

很多操作需要对象的实际类型信息，例如：

```java
p.getClass();
p instanceof Person;
(Person) obj;
p.virtualMethod();
```

HotSpot 会从对象头读取 `klass`，必要时解码 compressed class pointer，然后得到 `Klass*`：

```text
klass = decode_klass(object.header.klass)
```

拿到 `Klass*` 后，虚拟机可以完成：

- `instanceof` / `checkcast`：判断实际类型是否为目标类型或其子类型。
- `invokevirtual`：根据对象实际类型走 vtable/itable 做动态分派。
- `getClass()`：通过 klass 找到对应的 `java.lang.Class` 镜像对象。
- GC 扫描：根据类元数据知道对象中哪些偏移位置是引用字段。

这正是直接指针方案和句柄方案的关键区别之一：HotSpot 不需要通过句柄里的“对象实例地址 + 类型数据地址”两个地址来定位；对象头自己就能找到类型元数据。

---

## 7. 数组访问：数组地址 + base + index * scale

数组访问也是直接基于地址和偏移计算：

```java
int x = arr[i];
Object o = refs[i];
```

数组对象头中包含长度，元素区从固定 base offset 开始。元素地址可以抽象为：

```text
element_address = array_address + array_base_offset + i * element_size
```

其中：

- `int[]` 的元素是连续的 int 值。
- `Object[]` 的元素是 oop 或 narrowOop。
- 访问对象数组元素时，如果元素是 narrowOop，需要解码后得到对象地址。

---

## 8. GC 会移动对象，但普通访问仍不是句柄

直接指针方案有一个自然问题：GC 压缩整理堆时，对象地址会变化。

HotSpot 的处理方式不是给每次普通对象访问都加一层句柄，而是由 VM 和 GC 跟踪并更新所有活跃引用：

- GC 知道哪些位置保存着 oop：线程栈、寄存器、对象字段、静态字段、JNI handle 等。
- Safepoint 时，HotSpot 可以通过 OopMap 等元数据找到栈和寄存器中的 oop。
- 对象移动后，GC 会把这些引用更新为新对象地址或新的压缩引用编码。
- 某些并发 GC 还会通过读屏障、写屏障或 load barrier 处理转发、重映射等细节。

所以，普通执行路径仍然是：

```text
reference/oop -> 对象地址 -> 对象头 / 字段
```

只是这个地址由 JVM 和 GC 管理，程序不能把它当成 C/C++ 那种稳定裸指针使用。

---

## 9. HotSpot 中确实存在句柄，但不是普通对象访问主路径

HotSpot 也有句柄机制，典型场景包括：

- JNI 的 `jobject`。
- HotSpot VM 内部的 `Handle`。
- VM 代码在可能触发 GC 的位置临时保护对象引用。

这些句柄主要用于 VM/native 代码和 GC 协作：当 GC 移动对象时，句柄槽里的对象引用可以被更新，native 或 VM 代码继续通过句柄安全访问对象。

但这和普通 Java 代码访问 `p.age`、`p.friend` 不同。普通 Java 对象访问主路径仍然是直接指针或压缩直接指针。

---

## 10. 与“句柄访问”的对比

句柄访问可以抽象为：

```text
reference
    -> handle
        -> object data address
        -> type data address
```

直接指针访问可以抽象为：

```text
reference
    -> object address
        -> object header
            -> klass/type metadata
        -> fields
```

二者取舍大致是：

- 句柄访问：对象移动时只需要更新句柄中的对象地址，reference 本身可以不变；代价是每次访问多一次间接寻址。
- 直接指针访问：访问对象本身更快；代价是 GC 移动对象时需要更新所有活跃引用。

HotSpot 选择普通对象访问走直接指针体系，并通过 GC root 枚举、OopMap、屏障和压缩引用编码等机制处理对象移动与内存占用问题。

---

## 11. 一句话总结

HotSpot 普通 Java 对象访问是：

```text
reference/oop 直接定位到堆对象地址
    -> 对象头中的 klass 定位类型元数据
    -> 字段和数组元素通过固定偏移访问
```

Compressed Oops 只是把对象地址编码成更小的 32 位形式，使用时再解码；它仍然是直接指针方案，不是句柄访问。

