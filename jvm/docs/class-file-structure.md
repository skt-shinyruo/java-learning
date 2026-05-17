# Class 文件结构

`.class` 文件是 JVM 可以加载、校验、解析和执行的二进制格式。它不是 Java 源码的文本保存形式，而是一组按顺序紧凑排列的字节数据。

Class 文件主要由魔数、版本号、常量池、类信息、接口信息、字段信息、方法信息和属性信息组成。理解它时要抓住两点：

- Class 文件没有分隔符，所有字段都必须按照规范顺序读取。
- 很多结构本身不直接保存文本，而是保存常量池索引，再通过常量池找到类名、字段名、方法名、描述符等信息。

---

## 1. 总体结构

按照 JVM 规范，一个 Class 文件可以简化理解为下面的结构：

```text
ClassFile {
    u4             magic;
    u2             minor_version;
    u2             major_version;

    u2             constant_pool_count;
    cp_info        constant_pool[constant_pool_count - 1];

    u2             access_flags;
    u2             this_class;
    u2             super_class;

    u2             interfaces_count;
    u2             interfaces[interfaces_count];

    u2             fields_count;
    field_info     fields[fields_count];

    u2             methods_count;
    method_info    methods[methods_count];

    u2             attributes_count;
    attribute_info attributes[attributes_count];
}
```

其中：

- `u1`、`u2`、`u4` 分别表示 1、2、4 字节的无符号数。
- Class 文件使用大端序，也就是高位字节在前。
- 表结构通常以 `_info` 结尾，例如 `cp_info`、`field_info`、`method_info`、`attribute_info`。
- 多个同类结构通常用一个 `count` 字段加一组连续数据表示。

每个字段的作用如下：

| 字段 | 类型 | 作用 |
| --- | --- | --- |
| `magic` | `u4` | 魔数，固定为 `0xCAFEBABE`，用于识别 Class 文件 |
| `minor_version` | `u2` | 次版本号 |
| `major_version` | `u2` | 主版本号，决定 Class 文件需要的最低 JVM 版本 |
| `constant_pool_count` | `u2` | 常量池容量，实际有效条目数通常是 `constant_pool_count - 1` |
| `constant_pool` | `cp_info[]` | 常量池表，保存字面量和符号引用 |
| `access_flags` | `u2` | 当前类或接口的访问标志 |
| `this_class` | `u2` | 指向常量池中当前类名的索引 |
| `super_class` | `u2` | 指向常量池中父类名的索引 |
| `interfaces_count` | `u2` | 当前类直接实现或接口直接继承的接口数量 |
| `interfaces` | `u2[]` | 每一项都是一个常量池索引，指向接口名 |
| `fields_count` | `u2` | 字段表数量 |
| `fields` | `field_info[]` | 字段表，描述类变量和实例变量 |
| `methods_count` | `u2` | 方法表数量 |
| `methods` | `method_info[]` | 方法表，描述构造方法、普通方法和类初始化方法 |
| `attributes_count` | `u2` | Class 级属性数量 |
| `attributes` | `attribute_info[]` | Class 级属性表，例如 `SourceFile`、`InnerClasses` |

这里的“表”不是文本表格，而是二进制结构。比如 `field_info` 里又包含访问标志、名称索引、描述符索引和属性表。JVM 读取 Class 文件时会从前往后解析：先确认魔数和版本，再读取常量池，后面的类、字段、方法和属性才能通过常量池索引解释出具体含义。

---

## 2. 具体示例

用下面这个类观察 Class 文件结构：

```java
public class Demo {
    private int value = 10;

    public int add(int x) {
        return value + x;
    }
}
```

编译并查看字节码：

```bash
javac Demo.java
javap -v Demo.class
```

为了看见私有字段，也可以使用：

```bash
javap -v -p Demo.class
```

如果使用 Java 8 目标版本编译，Class 文件主版本号通常是 `52`。不同 JDK 编译器、不同编译参数会让输出略有差异，例如是否包含 `LocalVariableTable`、是否生成调试行号信息，但核心结构不会变。

这个例子的源码会被拆成几类信息：

```text
Demo
  ├── 类信息：public class Demo extends java/lang/Object
  ├── 字段信息：private int value
  ├── 构造方法：public Demo()
  │     ├── 调用 Object.<init>()
  │     └── 执行 this.value = 10
  ├── 普通方法：public int add(int)
  │     └── 执行 return this.value + x
  └── 属性信息：SourceFile、Code、LineNumberTable 等
```

需要注意：源码里的 `private int value = 10;` 看起来像字段的一部分，但实例字段初始化不是直接保存在字段表里，而是编译进构造方法的字节码。字段表只描述“有什么字段”，方法的 `Code` 属性才描述“运行时做什么”。

---

## 3. 魔数和版本号

`javap -v` 输出里可以看到类似信息：

```text
minor version: 0
major version: 52
```

Class 文件开头的 4 个字节是魔数：

```text
0xCAFEBABE
```

它用于标识当前文件是 Java Class 文件。JVM 或工具解析 `.class` 文件时，首先读取这 4 个字节。如果不是 `CAFEBABE`，通常会直接判定这不是合法的 Class 文件。

紧接着是两个版本号：

```text
u2 minor_version;
u2 major_version;
```

- `minor_version` 是次版本号，常见值是 `0`。
- `major_version` 是主版本号，表示这个 Class 文件使用的规范版本。

常见主版本号示例：

| Java 版本 | Class 主版本号 |
| --- | --- |
| Java 6 | `50` |
| Java 7 | `51` |
| Java 8 | `52` |
| Java 11 | `55` |
| Java 17 | `61` |

如果用较新的 JDK 编译，再放到较旧的 JVM 上运行，旧 JVM 可能不认识这个主版本号，于是抛出 `UnsupportedClassVersionError`。例如 Java 8 JVM 不能直接运行 Java 17 编译出的主版本号 `61` 的 Class 文件。

从二进制顺序看，一个 Java 8 编译的 Class 文件开头通常可以理解为：

```text
CA FE BA BE 00 00 00 34
│           │     │
│           │     └── major_version = 0x0034 = 52
│           └──────── minor_version = 0
└──────────────────── magic
```

---

## 4. 常量池

常量池是 Class 文件里的符号表，保存字面量，以及类、字段、方法、方法类型、动态调用点等符号引用信息。字节码指令通常不会把类名、字段名、方法名、描述符直接写在指令里，而是保存一个常量池索引，例如 `#2`、`#3`、`#6`。真正的信息放在常量池表中。

常量池在版本号后面出现：

```text
u2      constant_pool_count;
cp_info constant_pool[constant_pool_count - 1];
```

几个关键规则：

- 常量池有效索引从 `1` 开始，索引 `0` 通常表示“不引用任何常量池项”。
- `constant_pool_count` 表示容量，不是有效条目数，所以实际条目数通常是 `constant_pool_count - 1`。
- `CONSTANT_Long` 和 `CONSTANT_Double` 会占用两个常量池槽位。比如某个 `long` 常量占用了 `#5`，那么 `#6` 不能被其他常量使用。
- 每个 `cp_info` 都以 `tag` 开头，`tag` 决定后续数据结构。
- 常量池里的很多条目会继续引用其他常量池条目，形成“索引指向索引”的结构。

### 4.1 常量池条目总览

常量池中每个条目都是一个 `cp_info` 结构：

```text
cp_info {
    u1 tag;
    u1 info[];
}
```

`tag` 是 1 字节无符号数，表示当前条目的类型。Java 8 中常见常量池条目如下：

| tag | 类型 | 结构用途 |
| --- | --- | --- |
| `1` | `CONSTANT_Utf8` | 保存字符串文本，例如类名、字段名、方法名、描述符、属性名 |
| `3` | `CONSTANT_Integer` | 保存 `int` 常量 |
| `4` | `CONSTANT_Float` | 保存 `float` 常量 |
| `5` | `CONSTANT_Long` | 保存 `long` 常量，占两个槽位 |
| `6` | `CONSTANT_Double` | 保存 `double` 常量，占两个槽位 |
| `7` | `CONSTANT_Class` | 表示类或接口的符号引用 |
| `8` | `CONSTANT_String` | 表示字符串字面量引用 |
| `9` | `CONSTANT_Fieldref` | 表示字段符号引用 |
| `10` | `CONSTANT_Methodref` | 表示类方法符号引用 |
| `11` | `CONSTANT_InterfaceMethodref` | 表示接口方法符号引用 |
| `12` | `CONSTANT_NameAndType` | 把名称和描述符组合起来 |
| `15` | `CONSTANT_MethodHandle` | 表示方法句柄，用于 `invokedynamic` 等场景 |
| `16` | `CONSTANT_MethodType` | 表示方法类型，只保存方法描述符 |
| `18` | `CONSTANT_InvokeDynamic` | 表示动态调用点 |

Java 9 以后又增加了模块相关常量，例如 `CONSTANT_Module` 和 `CONSTANT_Package`；Java 11 又增加了 `CONSTANT_Dynamic`。本仓库以 Java 8 学习为主，所以正文重点放在 Java 8 会直接遇到的结构上。

### 4.2 常量池条目的二进制结构

`CONSTANT_Class` 表示类或接口名：

```text
CONSTANT_Class_info {
    u1 tag;         // 7
    u2 name_index;  // 指向 CONSTANT_Utf8
}
```

例如：

```text
#3  = Class #19
#19 = Utf8  Demo
```

`#3` 本身不保存 `Demo` 这几个字符，它只保存 `name_index = 19`，再由 `#19` 保存真正的文本。

`CONSTANT_Fieldref`、`CONSTANT_Methodref` 和 `CONSTANT_InterfaceMethodref` 结构相同：

```text
CONSTANT_Fieldref_info {
    u1 tag;                  // 9
    u2 class_index;           // 字段所属类
    u2 name_and_type_index;   // 字段名和字段描述符
}

CONSTANT_Methodref_info {
    u1 tag;                  // 10
    u2 class_index;           // 方法所属类
    u2 name_and_type_index;   // 方法名和方法描述符
}

CONSTANT_InterfaceMethodref_info {
    u1 tag;                  // 11
    u2 class_index;           // 接口名
    u2 name_and_type_index;   // 接口方法名和描述符
}
```

例如：

```text
#2  = Fieldref    #3.#18  // Demo.value:I
#3  = Class       #19     // Demo
#18 = NameAndType #5:#6   // value:I
#5  = Utf8        value
#6  = Utf8        I
```

这说明 `#2` 不是一个平铺字符串，而是一组引用关系：

```text
Fieldref #2
  ├── class_index         -> #3  -> #19 -> Demo
  └── name_and_type_index -> #18
        ├── name_index       -> #5 -> value
        └── descriptor_index -> #6 -> I
```

`CONSTANT_String` 表示字符串字面量：

```text
CONSTANT_String_info {
    u1 tag;           // 8
    u2 string_index;  // 指向 CONSTANT_Utf8
}
```

例如：

```text
#3  = String #18
#18 = Utf8   abc
```

`#3` 表示一个字符串常量，字符串内容来自 `#18`。注意它和 `CONSTANT_Utf8` 不一样：`Utf8` 是 Class 文件内部使用的文本存储方式，`String` 才表示 Java 语言层面的字符串字面量。

`CONSTANT_Integer` 和 `CONSTANT_Float` 用 4 字节保存值：

```text
CONSTANT_Integer_info {
    u1 tag;     // 3
    u4 bytes;   // int 的二进制值
}

CONSTANT_Float_info {
    u1 tag;     // 4
    u4 bytes;   // float 的 IEEE 754 表示
}
```

`CONSTANT_Long` 和 `CONSTANT_Double` 用 8 字节保存值，并占两个常量池槽位：

```text
CONSTANT_Long_info {
    u1 tag;          // 5
    u4 high_bytes;
    u4 low_bytes;
}

CONSTANT_Double_info {
    u1 tag;          // 6
    u4 high_bytes;
    u4 low_bytes;
}
```

如果常量池中有：

```text
#8 = Long 123456789L
```

那么 `#9` 这个槽位会被规范保留，后续可用索引从 `#10` 开始。这是手工解析常量池时最容易算错的位置之一。

`CONSTANT_NameAndType` 把“名称”和“描述符”放在一起：

```text
CONSTANT_NameAndType_info {
    u1 tag;                // 12
    u2 name_index;         // 指向 CONSTANT_Utf8
    u2 descriptor_index;   // 指向 CONSTANT_Utf8
}
```

例如字段 `value:I` 和方法 `add:(I)I` 都可以用 `NameAndType` 表示：

```text
NameAndType
  ├── name_index       -> value
  └── descriptor_index -> I

NameAndType
  ├── name_index       -> add
  └── descriptor_index -> (I)I
```

`CONSTANT_Utf8` 保存文本：

```text
CONSTANT_Utf8_info {
    u1 tag;             // 1
    u2 length;
    u1 bytes[length];
}
```

`length` 是字节数，不是 Java 字符数。Class 文件中的 `Utf8` 使用的是 JVM 规范定义的 modified UTF-8。类名、字段名、方法名、描述符、属性名最终都经常落到 `CONSTANT_Utf8` 上。

`CONSTANT_MethodHandle`、`CONSTANT_MethodType` 和 `CONSTANT_InvokeDynamic` 主要服务于动态调用、lambda 和方法句柄：

```text
CONSTANT_MethodHandle_info {
    u1 tag;                 // 15
    u1 reference_kind;      // 句柄种类，例如 getField、invokeStatic
    u2 reference_index;     // 指向字段或方法引用
}

CONSTANT_MethodType_info {
    u1 tag;                 // 16
    u2 descriptor_index;    // 指向方法描述符
}

CONSTANT_InvokeDynamic_info {
    u1 tag;                         // 18
    u2 bootstrap_method_attr_index;  // 指向 BootstrapMethods 属性中的某一项
    u2 name_and_type_index;          // 动态调用点的方法名和描述符
}
```

### 4.3 常量池不是简单平铺

Class 文件常量池不是把所有文本和引用简单平铺在一张表里。很多条目会继续引用其他常量池条目。

一个类引用通常是：

```text
CONSTANT_Class
  └── name_index -> CONSTANT_Utf8
```

一个字段引用通常是：

```text
CONSTANT_Fieldref
  ├── class_index         -> CONSTANT_Class
  └── name_and_type_index -> CONSTANT_NameAndType
                              ├── name_index       -> CONSTANT_Utf8
                              └── descriptor_index -> CONSTANT_Utf8
```

一个方法引用也类似：

```text
CONSTANT_Methodref
  ├── class_index         -> CONSTANT_Class
  └── name_and_type_index -> CONSTANT_NameAndType
                              ├── name_index       -> CONSTANT_Utf8
                              └── descriptor_index -> CONSTANT_Utf8
```

这种“条目引用条目”的结构让 Class 文件可以复用名称、描述符和类信息。字段表、方法表、属性表和字节码指令也会大量引用常量池索引，所以常量池是理解 Class 文件的中心。

### 4.4 一个具体例子

用下面这个类观察 Class 文件常量池：

```java
public class ConstantPoolDemo {
    public static void main(String[] args) {
        String s = "abc";
        System.out.println(s);
    }
}
```

编译并查看 Class 文件：

```bash
javac ConstantPoolDemo.java
javap -v ConstantPoolDemo.class
```

可以看到类似下面的常量池输出。不同 JDK 版本生成的条目编号可能略有差异，但组织方式一致。

```text
Constant pool:
 #1 = Methodref          #4.#15  // java/lang/Object."<init>":()V
 #2 = Fieldref           #16.#17 // java/lang/System.out:Ljava/io/PrintStream;
 #3 = String             #18     // abc
 #4 = Class              #19     // java/lang/Object
 #5 = Class              #20     // ConstantPoolDemo
 #6 = Methodref          #21.#22 // java/io/PrintStream.println:(Ljava/lang/String;)V

#16 = Class              #23     // java/lang/System
#17 = NameAndType        #24:#25 // out:Ljava/io/PrintStream;
#18 = Utf8               abc
#19 = Utf8               java/lang/Object
#20 = Utf8               ConstantPoolDemo
#21 = Class              #26     // java/io/PrintStream
#22 = NameAndType        #27:#28 // println:(Ljava/lang/String;)V
#23 = Utf8               java/lang/System
#24 = Utf8               out
#25 = Utf8               Ljava/io/PrintStream;
#26 = Utf8               java/io/PrintStream
#27 = Utf8               println
#28 = Utf8               (Ljava/lang/String;)V
```

先看字符串字面量：

```text
#3  = String #18
#18 = Utf8   abc
```

`#3` 是 `CONSTANT_String`，它并不直接保存字符内容，而是引用 `#18`。`#18` 是 `CONSTANT_Utf8`，保存文本 `abc`。

再看字段引用：

```text
#2  = Fieldref    #16.#17
#16 = Class       #23      // java/lang/System
#17 = NameAndType #24:#25  // out:Ljava/io/PrintStream;
#24 = Utf8        out
#25 = Utf8        Ljava/io/PrintStream;
```

`#2` 表示字段 `java.lang.System.out`，它由两部分组成：

- 字段属于哪个类：`java/lang/System`
- 字段名和字段描述符：`out:Ljava/io/PrintStream;`

再看方法引用：

```text
#6  = Methodref   #21.#22
#21 = Class       #26      // java/io/PrintStream
#22 = NameAndType #27:#28  // println:(Ljava/lang/String;)V
#27 = Utf8        println
#28 = Utf8        (Ljava/lang/String;)V
```

`#6` 表示方法 `java.io.PrintStream.println(String)`，其中：

- 方法所属类来自 `#21`。
- 方法名 `println` 来自 `#27`。
- 方法描述符 `(Ljava/lang/String;)V` 来自 `#28`。

### 4.5 字节码怎么引用 Class 文件常量池

同一个 `javap -v` 输出里，还能看到 `main` 方法的字节码：

```text
0: ldc           #3
2: astore_1
3: getstatic     #2
6: aload_1
7: invokevirtual #6
10: return
```

逐条看：

```text
ldc #3
```

表示从常量池第 `#3` 项加载常量，也就是字符串字面量 `"abc"`。

```text
getstatic #2
```

表示读取常量池第 `#2` 项描述的静态字段，也就是：

```java
java.lang.System.out
```

```text
invokevirtual #6
```

表示调用常量池第 `#6` 项描述的方法，也就是：

```java
java.io.PrintStream.println(String)
```

所以这个例子的关系可以整理成：

```text
ConstantPoolDemo.class
  └── constant_pool
        ├── #3  -> "abc"
        ├── #2  -> System.out
        └── #6  -> PrintStream.println(String)

main 方法字节码
  ├── ldc #3
  ├── getstatic #2
  └── invokevirtual #6
```

一句话总结：

> Class 文件常量池是 `.class` 文件里的符号索引表；字节码用索引引用它，类名、字段名、方法名和描述符等信息都通过常量池条目组织起来。

---

## 5. 类信息

常量池之后，Class 文件开始描述“这个类型是谁”。这一段不保存字段、方法的具体内容，只保存当前类型的身份、父类以及类型级访问标志。

```text
u2 access_flags;
u2 this_class;
u2 super_class;
```

`Demo` 的类信息大致是：

```text
public class Demo
  flags: ACC_PUBLIC, ACC_SUPER
  this_class: #3   // Demo
  super_class: #4  // java/lang/Object
```

这三个字段共同回答三个问题：

```text
access_flags -> 这个类型是什么形态：class / interface / annotation / enum / abstract / final
this_class   -> 当前类型的内部名是什么
super_class  -> 直接父类是谁
```

源码中的：

```java
public class Demo
```

在 Class 文件里会明确记录成：

```java
public class Demo extends Object
```

即使源码没有写 `extends Object`，`super_class` 也会指向 `java/lang/Object`。只有 `java/lang/Object` 自己比较特殊，它没有父类，所以它的 `super_class` 为 `0`。

类访问标志描述的是“类型级别”的性质。常见标志包括：

| 标志 | 位值 | 含义 |
| --- | --- | --- |
| `ACC_PUBLIC` | `0x0001` | 可以被包外访问 |
| `ACC_FINAL` | `0x0010` | 不能被继承 |
| `ACC_SUPER` | `0x0020` | 使用较新的 `invokespecial` 语义，现代 Java 类通常都有 |
| `ACC_INTERFACE` | `0x0200` | 当前结构表示接口 |
| `ACC_ABSTRACT` | `0x0400` | 抽象类或接口，不能直接实例化 |
| `ACC_SYNTHETIC` | `0x1000` | 不是源码中直接声明，而是编译器生成 |
| `ACC_ANNOTATION` | `0x2000` | 注解类型 |
| `ACC_ENUM` | `0x4000` | 枚举类型 |

`access_flags` 是按位组合的。例如普通 `public class Demo` 常见值是：

```text
ACC_PUBLIC | ACC_SUPER = 0x0001 | 0x0020 = 0x0021
```

不同类型的标志组合不同：

```text
public class Demo
  -> ACC_PUBLIC, ACC_SUPER

public final class Demo
  -> ACC_PUBLIC, ACC_FINAL, ACC_SUPER

public interface Task
  -> ACC_PUBLIC, ACC_INTERFACE, ACC_ABSTRACT

public @interface Mark
  -> ACC_PUBLIC, ACC_INTERFACE, ACC_ABSTRACT, ACC_ANNOTATION

public enum Color
  -> ACC_PUBLIC, ACC_FINAL, ACC_SUPER, ACC_ENUM
```

这些标志不是 Java 关键字的简单文本保存，而是编译器根据源码语义写入的位集合。JVM 校验阶段会检查某些组合是否合法。例如一个普通类不能同时既是 `ACC_FINAL` 又是 `ACC_ABSTRACT`，因为这在语义上互相冲突。

Class 文件中的类名使用内部名，不使用源码里的点号。例如：

```text
java.lang.Object   -> java/lang/Object
java.util.Map.Entry -> java/util/Map$Entry
```

`this_class` 和 `super_class` 都指向 `CONSTANT_Class_info`，而 `CONSTANT_Class_info` 再指向 `CONSTANT_Utf8_info`。因此类信息的完整解析链路通常是：

```text
this_class  -> CONSTANT_Class -> CONSTANT_Utf8 -> Demo
super_class -> CONSTANT_Class -> CONSTANT_Utf8 -> java/lang/Object
```

类信息里不会记录完整继承树，只记录直接父类。比如：

```java
class A {}
class B extends A {}
class C extends B {}
```

`C.class` 的 `super_class` 只会指向 `B`，不会同时记录 `A` 和 `Object`。JVM 需要继续加载 `B.class`、`A.class`，才能得到完整继承链。

类信息也不会记录字段、方法、接口方法实现或注解内容。它只是 Class 文件中“类型身份”的入口，后面的接口表、字段表、方法表和属性表会继续补充其他部分。

---

## 6. 接口信息

类信息后面紧接着是接口表。Java 类只有一个直接父类，但可以实现多个接口；接口本身也可以继承多个父接口。Class 文件用接口表保存这些“直接接口关系”。

```text
u2 interfaces_count;
u2 interfaces[interfaces_count];
```

`interfaces_count` 表示接口数量。`interfaces` 数组里的每一项都是一个 `u2` 常量池索引，指向一个 `CONSTANT_Class_info`，再由它找到接口的内部名。

例如源码：

```java
public class Demo implements Runnable, AutoCloseable {
    public void run() {
    }

    public void close() {
    }
}
```

接口表可以简化理解为：

```text
interfaces_count: 2
interfaces[0]: #x  // java/lang/Runnable
interfaces[1]: #y  // java/lang/AutoCloseable
```

解析链路和 `this_class` 类似：

```text
interfaces[0] -> CONSTANT_Class -> CONSTANT_Utf8 -> java/lang/Runnable
interfaces[1] -> CONSTANT_Class -> CONSTANT_Utf8 -> java/lang/AutoCloseable
```

接口表有几个重要边界。

第一，它只记录直接接口，不递归展开父接口。比如：

```java
interface A {}
interface B extends A {}
class Demo implements B {}
```

`Demo.class` 的接口表只需要记录 `B`。`A` 是 `B.class` 自己的父接口关系，不会被复制到 `Demo.class` 里。

第二，它不记录从父类继承来的接口。例如：

```java
class Parent implements Serializable {}
class Child extends Parent {}
```

`Child.class` 的接口表可以为空，因为 `Serializable` 是从 `Parent` 的类型关系中继承来的，不是 `Child` 直接声明的接口。

第三，接口表只描述类型关系，不描述方法实现。接口方法是否被实现，要看方法表。如果当前类实现了 `Runnable.run()`，`run` 方法会出现在 `methods` 中；如果当前类是抽象类，它可以在接口表里声明实现接口，但不提供所有接口方法的 `method_info`。

第四，如果当前 Class 文件表示的是接口，那么 `interfaces` 保存的是这个接口直接继承的父接口。例如：

```java
interface Child extends Runnable, AutoCloseable {}
```

这时 `Child.class` 的 `interfaces_count` 也是 `2`，含义是“直接父接口数量”，不是“实现接口数量”。

如果没有直接接口，`interfaces_count` 为 `0`，后面不会再占用接口数组字节。手工解析时要注意：`count = 0` 表示数组长度为 0，不是后面还有一个值为 0 的接口项。

---

## 7. 字段信息

字段表描述类或接口中“声明出来的字段”。Java 语言里的字段包括实例变量和类变量，但不包括方法内部的局部变量。局部变量属于方法执行过程中的 `Code` 属性，不属于字段表。

源码中的字段：

```java
private int value = 10;
```

字段表中主要记录字段名、字段类型和访问标志：

```text
private int value;
  descriptor: I
  flags: ACC_PRIVATE
```

字段结构可以简化理解为：

```text
field_info {
    u2 access_flags;
    u2 name_index;
    u2 descriptor_index;
    u2 attributes_count;
    attribute_info attributes[attributes_count];
}
```

这几个字段的重点是“字段声明”，不是“字段执行逻辑”：

- `access_flags` 表示 `private`、`static`、`final`、`volatile`、`transient` 等字段标志。
- `name_index` 指向常量池中的字段名，例如 `value`。
- `descriptor_index` 指向常量池中的字段描述符，例如 `I`。
- `attributes_count` 和 `attributes` 保存字段级属性，例如 `ConstantValue`、`Signature`、注解属性。

对 `private int value` 来说，字段表解析链路可以理解为：

```text
field_info
  ├── access_flags      -> ACC_PRIVATE
  ├── name_index        -> CONSTANT_Utf8 -> value
  ├── descriptor_index  -> CONSTANT_Utf8 -> I
  └── attributes        -> 可能为空，也可能保存 ConstantValue / Signature / 注解
```

常见字段访问标志包括：

| 标志 | 位值 | 含义 |
| --- | --- | --- |
| `ACC_PUBLIC` | `0x0001` | `public` 字段 |
| `ACC_PRIVATE` | `0x0002` | `private` 字段 |
| `ACC_PROTECTED` | `0x0004` | `protected` 字段 |
| `ACC_STATIC` | `0x0008` | 静态字段 |
| `ACC_FINAL` | `0x0010` | `final` 字段 |
| `ACC_VOLATILE` | `0x0040` | `volatile` 字段 |
| `ACC_TRANSIENT` | `0x0080` | `transient` 字段 |
| `ACC_SYNTHETIC` | `0x1000` | 编译器生成字段 |
| `ACC_ENUM` | `0x4000` | 枚举常量 |

字段的 `access_flags` 也可以按位组合。比如：

```text
private static final int SIZE
-> ACC_PRIVATE | ACC_STATIC | ACC_FINAL
-> 0x0002 | 0x0008 | 0x0010 = 0x001A
```

字段标志有一些语义约束：

- `ACC_PUBLIC`、`ACC_PRIVATE`、`ACC_PROTECTED` 三者最多只能出现一个。
- `ACC_FINAL` 和 `ACC_VOLATILE` 不能同时出现，因为一个表示值不可变，一个表示可被并发更新。
- 接口中声明的字段天然是 `public static final`，对应字段表通常会带 `ACC_PUBLIC`、`ACC_STATIC`、`ACC_FINAL`。

字段描述符用紧凑字符串表示字段类型：

| 描述符 | Java 类型 |
| --- | --- |
| `B` | `byte` |
| `C` | `char` |
| `D` | `double` |
| `F` | `float` |
| `I` | `int` |
| `J` | `long` |
| `S` | `short` |
| `Z` | `boolean` |
| `V` | `void`，只用于方法返回值 |
| `Ljava/lang/String;` | `java.lang.String` |
| `[I` | `int[]` |
| `[[Ljava/lang/String;` | `java.lang.String[][]` |

对象类型描述符以 `L` 开头，以 `;` 结束，中间使用内部名：

```text
java.lang.String  -> Ljava/lang/String;
java.util.List    -> Ljava/util/List;
```

数组类型描述符以 `[` 开头，一维数组一个 `[`，二维数组两个 `[`：

```text
int[]             -> [I
String[]          -> [Ljava/lang/String;
String[][]        -> [[Ljava/lang/String;
```

注意：`value = 10` 这段实例字段初始化逻辑通常不直接放在字段结构里，而是被编译进构造方法的 `Code` 属性中。字段表描述的是字段本身，不描述每次创建对象时如何给字段赋值。

这点可以用两类字段区分：

```java
private int value = 10;
private static int count = initCount();
public static final int SIZE = 10;
```

它们在 Class 文件中的落点不同：

```text
value
  -> 字段表记录 private int value
  -> this.value = 10 编译进 <init> 的 Code

count
  -> 字段表记录 private static int count
  -> count = initCount() 编译进 <clinit> 的 Code

SIZE
  -> 字段表记录 public static final int SIZE
  -> 编译期常量值 10 可以放进 ConstantValue 属性
```

`ConstantValue` 是最容易混淆的字段属性：

```java
public static final int SIZE = 10;
```

这种字段可能带有 `ConstantValue` 属性，用来记录常量值 `10`。它常见于 `static final` 的基本类型和 `String` 编译期常量。普通实例字段初始化、复杂表达式初始化、对象创建等逻辑仍然会进入方法字节码，例如构造方法或类初始化方法 `<clinit>`。

字段的属性表还可能包含泛型签名和注解。比如：

```java
private List<String> names;
```

编译后字段的描述符仍然可能是 `Ljava/util/List;`，而真实泛型信息会落在 `Signature` 属性中。也就是说，字段描述符负责“运行时类型轮廓”，`Signature` 负责“编译期泛型信息”。

字段表还有两个边界值得注意。

第一，字段表不会列出从父类或父接口继承来的字段。子类能访问继承字段，是类型系统和运行时解析的结果，不是因为子类 Class 文件把父类字段复制了一份。

第二，字段表可能出现源码中看不到的字段。例如内部类为了访问外部类实例，编译器可能生成类似 `this$0` 的合成字段，并用 `ACC_SYNTHETIC` 或命名约定表示它不是源码直接声明的字段。

第三，Class 文件层面的字段唯一性规则和 Java 源码不同。Java 源码不允许同一个类里出现两个同名字段；Class 文件更底层，它关心的是名称和描述符组合，工具生成字节码时可能构造出 Java 源码写不出来的形式。

---

## 8. 方法信息和构造方法

方法表描述类或接口中声明的方法。这里的“方法”不只包括源码中写出来的普通方法，还包括实例构造器 `<init>`、类初始化方法 `<clinit>`，以及编译器为了泛型、内部类、lambda 等特性生成的合成方法。

方法表的位置在字段表之后：

```text
u2          methods_count;
method_info methods[methods_count];
```

`method_info` 的结构和 `field_info` 很像：

```text
method_info {
    u2 access_flags;
    u2 name_index;
    u2 descriptor_index;
    u2 attributes_count;
    attribute_info attributes[attributes_count];
}
```

`method_info` 的核心职责是描述“方法声明”和“方法附加信息”：

- `access_flags` 表示 `public`、`private`、`static`、`final`、`abstract`、`native` 等方法标志。
- `name_index` 指向常量池中的方法名。
- `descriptor_index` 指向常量池中的方法描述符。
- `attributes` 保存方法属性，最重要的是 `Code` 属性。

一个普通方法的解析链路通常是：

```text
method_info
  ├── access_flags      -> ACC_PUBLIC
  ├── name_index        -> CONSTANT_Utf8 -> add
  ├── descriptor_index  -> CONSTANT_Utf8 -> (I)I
  └── attributes        -> Code / Exceptions / Signature / 注解 ...
```

常见方法访问标志包括：

| 标志 | 位值 | 含义 |
| --- | --- | --- |
| `ACC_PUBLIC` | `0x0001` | `public` 方法 |
| `ACC_PRIVATE` | `0x0002` | `private` 方法 |
| `ACC_PROTECTED` | `0x0004` | `protected` 方法 |
| `ACC_STATIC` | `0x0008` | 静态方法 |
| `ACC_FINAL` | `0x0010` | `final` 方法 |
| `ACC_SYNCHRONIZED` | `0x0020` | `synchronized` 方法 |
| `ACC_BRIDGE` | `0x0040` | 桥接方法，常见于泛型擦除 |
| `ACC_VARARGS` | `0x0080` | 可变参数方法 |
| `ACC_NATIVE` | `0x0100` | 本地方法，没有 Java 字节码 |
| `ACC_ABSTRACT` | `0x0400` | 抽象方法，没有 Java 字节码 |
| `ACC_STRICT` | `0x0800` | `strictfp` 方法 |
| `ACC_SYNTHETIC` | `0x1000` | 编译器生成的方法 |

方法的 `access_flags` 也会按位组合。例如：

```text
public static void main(String[] args)
-> ACC_PUBLIC | ACC_STATIC = 0x0001 | 0x0008 = 0x0009
```

方法描述符是方法重载和调用解析的关键。它用下面的形式表达参数列表和返回值：

```text
(参数描述符列表)返回值描述符
```

几个例子：

```text
void run()                    -> ()V
int add(int x)                -> (I)I
String join(String s, int n)  -> (Ljava/lang/String;I)Ljava/lang/String;
void main(String[] args)      -> ([Ljava/lang/String;)V
int sum(int[] values)         -> ([I)I
```

方法可以重载，本质上就是同名方法拥有不同描述符：

```java
void print(int x)
void print(String s)
```

在 Class 文件里会变成：

```text
print:(I)V
print:(Ljava/lang/String;)V
```

方法表不会列出从父类继承但没有重写的方法。子类能调用父类方法，是运行时方法解析和虚方法分派的结果，不是因为子类 Class 文件复制了父类方法。

### 8.1 `<init>`：实例构造器

构造方法在 Class 文件中不是名为 `Demo` 的普通方法，而是名为 `<init>` 的特殊方法。虽然源码里没有显式写构造方法，编译器也会生成默认构造方法，并把实例字段初始化放进去：

```java
public Demo() {
    super();
    this.value = 10;
}
```

对应字节码大致是：

```text
public Demo();
  descriptor: ()V
  flags: ACC_PUBLIC
  Code:
    0: aload_0
    1: invokespecial #1  // Method java/lang/Object."<init>":()V
    4: aload_0
    5: bipush        10
    7: putfield      #2  // Field value:I
   10: return
```

逐条看：

- `aload_0`：把局部变量表第 `0` 个槽位里的 `this` 加载到操作数栈。
- `invokespecial #1`：调用父类构造方法，也就是 `super()`。
- `bipush 10`：把常量 `10` 压入操作数栈。
- `putfield #2`：给常量池第 `#2` 项描述的字段赋值，也就是 `this.value`。
- `return`：构造方法返回，构造方法返回类型固定是 `void`。

方法描述符 `()V` 的意思是：

```text
()V
│ │
│ └── 返回 void
└──── 没有参数
```

如果源码中有多个构造方法，Class 文件中会出现多个名为 `<init>` 的方法，它们通过不同的方法描述符区分。例如：

```text
<init>:()V
<init>:(I)V
<init>:(Ljava/lang/String;I)V
```

实例构造器有几个特点：

- 方法名固定为 `<init>`。
- 返回值描述符固定是 `V`。
- 每个构造器通常先调用另一个构造器或父类构造器。
- 实例字段初始化语句会被编译器插入到构造器字节码里。

### 8.2 `<clinit>`：类初始化方法

静态字段初始化和 `static {}` 代码块会被编译进名为 `<clinit>` 的类初始化方法。`<init>` 用于对象初始化，`<clinit>` 用于类初始化。

`<clinit>` 的特点是：

- 由编译器生成，不对应源码中的某个方法名。
- 只负责类初始化逻辑，例如静态字段赋值和静态代码块。
- 在类第一次主动使用时由 JVM 调用。
- 不是每个类都有 `<clinit>`。如果没有静态初始化逻辑，编译器可以不生成它。

如果类里出现了复杂的静态初始化，例如：

```java
static final String NAME = buildName();
static {
    init();
}
```

这些逻辑通常都会进入 `<clinit>`。

### 8.3 编译器生成的方法

方法表中可能出现源码里没有的方法。常见情况包括：

- **桥接方法**：泛型擦除后，为了保持多态语义，编译器可能生成 `ACC_BRIDGE`、`ACC_SYNTHETIC` 方法。
- **访问辅助方法**：某些 Java 版本中，内部类访问外部类私有成员时可能生成合成访问方法。
- **lambda 辅助方法**：lambda 表达式可能生成私有静态方法或私有实例方法，再由 `invokedynamic` 关联。

例如泛型场景：

```java
class StringBox implements Comparable<StringBox> {
    public int compareTo(StringBox other) {
        return 0;
    }
}
```

编译器可能额外生成一个桥接方法，帮助擦除后的 `Comparable.compareTo(Object)` 转发到 `compareTo(StringBox)`。这种方法通常带有 `ACC_BRIDGE` 和 `ACC_SYNTHETIC`。

### 8.4 没有 Code 的方法

普通 Java 方法的执行逻辑放在 `Code` 属性里，但不是所有方法都有 `Code`：

```java
abstract void run();
native int size();
```

`abstract` 方法没有方法体，`native` 方法由本地代码实现，所以它们的 `method_info` 中不会有普通 Java 字节码的 `Code` 属性。方法表仍然记录它们的名称、描述符和访问标志，因为这些信息对链接、校验、反射和调用解析仍然重要。

---

## 9. 普通方法和 Code 属性

方法表描述“这个方法是什么”，`Code` 属性描述“这个方法怎么执行”。普通 Java 方法的方法体最终会被编译成 `Code` 属性里的 JVM 字节码。

源码中的方法：

```java
public int add(int x) {
    return value + x;
}
```

`method_info` 中会记录方法声明：

```text
public int add(int);
  descriptor: (I)I
  flags: ACC_PUBLIC
```

真正的执行逻辑在它的 `Code` 属性里：

```text
Code:
  stack=2, locals=2
  0: aload_0
  1: getfield      #2  // Field value:I
  4: iload_1
  5: iadd
  6: ireturn
```

### 9.1 Code 属性的结构

`Code` 属性可以简化理解为：

```text
Code_attribute {
    u2 attribute_name_index;   // 指向常量池中的 "Code"
    u4 attribute_length;
    u2 max_stack;
    u2 max_locals;
    u4 code_length;
    u1 code[code_length];
    u2 exception_table_length;
    exception_table[exception_table_length];
    u2 attributes_count;
    attribute_info attributes[attributes_count];
}
```

它的字段不是简单“附加说明”，而是 JVM 执行和校验方法体所需的核心数据：

- `max_stack`：操作数栈最大深度，JVM 校验和执行方法时会使用。
- `max_locals`：局部变量表所需槽位数，包括 `this`、参数和局部变量。
- `code_length` 和 `code`：真正的 JVM 字节码指令序列。
- `exception_table`：异常处理表，记录 `try-catch-finally` 对应的字节码范围和处理入口。
- 嵌套 `attributes`：方法字节码内部的属性，例如 `LineNumberTable`、`LocalVariableTable`、`StackMapTable`。

`code[]` 不是 `javap` 输出里的文本，而是一串字节。每条指令由 1 字节操作码和可能存在的操作数组成。`javap` 把这些字节反汇编成人能读懂的形式，并显示字节码偏移量：

```text
0: aload_0
1: getfield #2
4: iload_1
5: iadd
6: ireturn
```

这里的 `0`、`1`、`4`、`5`、`6` 是字节码偏移量，不是源码行号。`getfield #2` 占用多个字节，所以后一条指令从偏移量 `4` 开始。

### 9.2 操作数栈和局部变量表

JVM 字节码大多围绕操作数栈执行。`add` 方法可以按栈变化理解：

```text
aload_0
  -> 把 this 压栈

getfield #2
  -> 弹出 this，读取 value，把 int 值压栈

iload_1
  -> 把局部变量槽位 1 的 int 参数 x 压栈

iadd
  -> 弹出两个 int，相加，把结果压栈

ireturn
  -> 弹出 int 作为返回值
```

对实例方法 `add(int x)` 来说，局部变量表的槽位是：

```text
slot 0 -> this
slot 1 -> x
```

所以字节码使用 `aload_0` 读取 `this`，使用 `iload_1` 读取参数 `x`。如果是静态方法，就没有隐式的 `this`，第一个参数会从 `slot 0` 开始。

`max_stack=2` 是因为这个方法执行到 `iadd` 前，操作数栈最多同时放两个 `int`。`max_locals=2` 是因为它只需要 `this` 和参数 `x` 两个槽位。

还要注意 `long` 和 `double` 会占两个局部变量槽位。例如实例方法：

```java
void f(long a, int b)
```

局部变量表大致是：

```text
slot 0 -> this
slot 1 -> a 的高/低槽位之一
slot 2 -> a 的另一个槽位
slot 3 -> b
```

### 9.3 异常表

`Code` 结构里的异常表描述异常处理范围。它的每一项通常包括：

```text
exception_table {
    u2 start_pc;
    u2 end_pc;
    u2 handler_pc;
    u2 catch_type;
}
```

- `start_pc` 和 `end_pc` 表示受保护的字节码范围。
- `handler_pc` 表示异常处理代码的入口。
- `catch_type` 指向常量池中的异常类；如果是 `finally` 语义，编译器可能生成更复杂的字节码组合来模拟。

例如：

```java
try {
    a();
} catch (IOException e) {
    b();
}
```

对应的 `Code` 中会有一段异常表，JVM 根据这张表决定抛出异常后跳到哪里。

异常表和 Java 源码的 `try-catch` 不是一一按文本保存的关系。编译器会把源码控制流编译成字节码范围和处理入口，JVM 运行时只看字节码偏移量和异常类型。

### 9.4 Code 内部的调试和校验属性

`LineNumberTable` 建立字节码偏移量和源码行号之间的关系，便于调试和堆栈追踪：

```text
LineNumberTable_attribute {
    u2 attribute_name_index;
    u4 attribute_length;
    u2 line_number_table_length;
    line_number_info line_number_table[line_number_table_length];
}

line_number_info {
    u2 start_pc;      // 字节码偏移量
    u2 line_number;   // 源码行号
}
```

`LocalVariableTable` 保存局部变量名、作用范围和槽位信息，便于调试器显示变量名：

```text
LocalVariableTable_attribute {
    u2 attribute_name_index;
    u4 attribute_length;
    u2 local_variable_table_length;
    local_variable_info local_variable_table[local_variable_table_length];
}

local_variable_info {
    u2 start_pc;           // 变量作用范围起点
    u2 length;             // 变量作用范围长度
    u2 name_index;         // 变量名
    u2 descriptor_index;   // 变量描述符
    u2 index;              // 局部变量表槽位
}
```

`StackMapTable` 服务于字节码验证。它保存一组压缩后的栈映射帧：

```text
StackMapTable_attribute {
    u2 attribute_name_index;
    u4 attribute_length;
    u2 number_of_entries;
    stack_map_frame entries[number_of_entries];
}
```

每个 `stack_map_frame` 描述某个字节码偏移量处的局部变量表类型和操作数栈类型。JVM 在验证方法时，不需要从头把每条指令的类型都重新推导一遍，而是依靠这些栈帧摘要快速检查类型一致性。控制流越复杂，`StackMapTable` 越重要。

`Code` 属性还可能包含 `RuntimeVisibleTypeAnnotations`、`RuntimeInvisibleTypeAnnotations` 等更细粒度的属性，这些通常用于类型注解和高级语言特性。日常学习时先掌握 `LineNumberTable`、`LocalVariableTable`、`StackMapTable` 就够理解大多数 Java 字节码了。

---

## 10. 属性表

Class 文件、字段、方法和 `Code` 都可以带属性。属性表是 Class 文件最重要的扩展机制：ClassFile 主结构保持稳定，新语言特性、调试信息、泛型、注解、动态调用等内容主要通过新增属性来承载。

理解属性表时要先区分“属性挂在哪里”：

```text
ClassFile.attributes  -> 描述整个类
field_info.attributes -> 描述某个字段
method_info.attributes -> 描述某个方法
Code.attributes -> 描述某段方法字节码
```

同一个属性名放在不同位置，含义和合法性可能不同。例如 `Code` 只能作为方法属性，不能挂在字段上；`LineNumberTable` 通常在 `Code` 内部，而不是直接挂在 ClassFile 上。

属性结构统一是：

```text
attribute_info {
    u2 attribute_name_index;
    u4 attribute_length;
    u1 info[attribute_length];
}
```

其中：

- `attribute_name_index` 指向常量池中的属性名，例如 `Code`、`SourceFile`。
- `attribute_length` 表示属性内容长度。
- `info` 是属性自己的数据，不同属性有不同结构。

因为属性带有名称和长度，解析器可以根据属性名决定如何解释 `info`。如果某个工具不关心某些非关键属性，也可以根据 `attribute_length` 跳过对应字节。

这带来两个结果：

- JVM 规范可以在不破坏旧结构的情况下增加新属性。
- 工具解析 Class 文件时，必须先读出 `attribute_name_index` 和 `attribute_length`，再决定是否深入解析 `info`。

常见属性包括：

| 属性 | 常见位置 | 作用 |
| --- | --- | --- |
| `Code` | 方法 | 保存方法字节码 |
| `LineNumberTable` | `Code` 内部 | 保存字节码偏移量和源码行号的关系 |
| `LocalVariableTable` | `Code` 内部 | 保存局部变量调试信息 |
| `LocalVariableTypeTable` | `Code` 内部 | 保存泛型相关的局部变量调试信息 |
| `StackMapTable` | `Code` 内部 | 保存类型校验所需的栈映射帧 |
| `SourceFile` | Class | 保存源文件名 |
| `ConstantValue` | 字段 | 保存静态编译期常量值 |
| `Exceptions` | 方法 | 保存方法声明抛出的受检异常 |
| `Signature` | Class、字段、方法 | 保存泛型签名 |
| `RuntimeVisibleAnnotations` | Class、字段、方法 | 保存运行时可见注解 |
| `RuntimeInvisibleAnnotations` | Class、字段、方法 | 保存运行时不可见注解 |
| `InnerClasses` | Class | 保存内部类信息 |
| `EnclosingMethod` | Class | 保存局部类或匿名类所在的外部方法 |
| `BootstrapMethods` | Class | 保存动态调用点和 lambda 相关的引导方法 |
| `RuntimeVisibleTypeAnnotations` | Class、字段、方法、Code | 保存运行时可见的类型注解 |
| `RuntimeInvisibleTypeAnnotations` | Class、字段、方法、Code | 保存运行时不可见的类型注解 |

例如：

```text
SourceFile: "Demo.java"
LineNumberTable:
  line 2: 0
  line 5: 0
```

这些信息通常不是程序执行逻辑本身，但对调试、反射、注解处理和工具分析很重要。

不同层级的属性有不同用途：

```text
ClassFile.attributes
  ├── SourceFile
  ├── InnerClasses
  ├── Signature
  ├── BootstrapMethods
  └── RuntimeVisibleAnnotations

field_info.attributes
  ├── ConstantValue
  ├── Signature
  └── RuntimeVisibleAnnotations

method_info.attributes
  ├── Code
  ├── Exceptions
  ├── Signature
  └── RuntimeVisibleAnnotations

Code.attributes
  ├── LineNumberTable
  ├── LocalVariableTable
  └── StackMapTable
```

可以把属性表理解为 Class 文件的“扩展插槽”。基础结构负责描述类、字段和方法的骨架，属性表负责承载调试信息、泛型签名、注解、内部类、异常声明、字节码和动态调用等可扩展信息。

下面把几个常见属性展开说清楚。

`SourceFile` 只保存一个源文件名索引，例如 `Demo.java`。它不是源码本身，只是让堆栈、调试器和工具知道这个类来源于哪个文件：

```text
SourceFile_attribute {
    u2 attribute_name_index;
    u4 attribute_length;   // 固定为 2
    u2 sourcefile_index;   // 指向 CONSTANT_Utf8
}
```

`ConstantValue` 只会出现在静态 `final` 字段上，用来保存编译期常量值：

```text
ConstantValue_attribute {
    u2 attribute_name_index;
    u4 attribute_length;    // 固定为 2
    u2 constantvalue_index; // 指向常量池中的字面量
}
```

例如：

```java
public static final int SIZE = 10;
```

编译后可以直接把 `10` 放在 `ConstantValue` 里，这样类初始化时就不需要再执行字节码去赋值。

`Exceptions` 记录方法声明会抛出的受检异常列表：

```text
Exceptions_attribute {
    u2 attribute_name_index;
    u4 attribute_length;
    u2 number_of_exceptions;
    u2 exception_index_table[number_of_exceptions];
}
```

`exception_index_table` 里的每一项都指向一个 `CONSTANT_Class`，也就是异常类型。例如：

```java
public void read() throws IOException
```

它会把 `IOException` 的类引用写进属性里，供反射和工具查看。

`Signature` 用来保存泛型签名：

```text
Signature_attribute {
    u2 attribute_name_index;
    u4 attribute_length;   // 固定为 2
    u2 signature_index;    // 指向 CONSTANT_Utf8
}
```

原因是字段描述符和方法描述符只能表达擦除后的运行时类型，比如 `List<String>` 运行时仍然可能表现为 `List`。`Signature` 才保留泛型参数信息。

`InnerClasses` 记录内部类、成员类、局部类、匿名类之间的关系：

```text
InnerClasses_attribute {
    u2 attribute_name_index;
    u4 attribute_length;
    u2 number_of_classes;
    classes[number_of_classes];
}

classes {
    u2 inner_class_info_index;   // 内部类
    u2 outer_class_info_index;   // 外部类，没有则为 0
    u2 inner_name_index;         // 内部类简单名，匿名类可为 0
    u2 inner_class_access_flags; // 内部类访问标志
}
```

`EnclosingMethod` 用于局部类和匿名类，说明它们定义在外部的哪个类或哪个方法里：

```text
EnclosingMethod_attribute {
    u2 attribute_name_index;
    u4 attribute_length;    // 固定为 4
    u2 class_index;         // 外部类
    u2 method_index;        // 外部方法或构造方法，没有则为 0
}
```

`BootstrapMethods` 是动态调用的关键。它保存 `invokedynamic`、lambda 表达式、方法句柄相关的引导方法信息：

```text
BootstrapMethods_attribute {
    u2 attribute_name_index;
    u4 attribute_length;
    u2 num_bootstrap_methods;
    bootstrap_method bootstrap_methods[num_bootstrap_methods];
}

bootstrap_method {
    u2 bootstrap_method_ref;       // 指向 CONSTANT_MethodHandle
    u2 num_bootstrap_arguments;
    u2 bootstrap_arguments[num_bootstrap_arguments];
}
```

`CONSTANT_InvokeDynamic` 中的 `bootstrap_method_attr_index` 会指向 `BootstrapMethods` 属性中的某一项。这个关系可以简化为：

```text
invokedynamic 指令
  └── CONSTANT_InvokeDynamic
        ├── bootstrap_method_attr_index -> BootstrapMethods[n]
        │     └── bootstrap_method_ref -> CONSTANT_MethodHandle
        └── name_and_type_index -> 动态调用点的方法名和描述符
```

比如 Java 8 的 lambda 编译后，常常会出现一个 `invokedynamic` 指令。执行时 JVM 通过 `BootstrapMethods` 找到引导方法，引导方法返回一个调用点，之后该调用点就可以像普通方法调用一样被执行和优化。

`RuntimeVisibleAnnotations` 和 `RuntimeInvisibleAnnotations` 分别保存运行时可见和不可见的注解。前者会被反射 API 看到，后者通常只供编译器或工具使用。

`RuntimeVisibleTypeAnnotations` 和 `RuntimeInvisibleTypeAnnotations` 是更细一层的类型注解属性。它们描述的是“类型使用位置上的注解”，例如泛型参数、强转、`throws`、`instanceof` 等位置上的注解。

---

## 11. 从 Class 文件到运行时

Class 文件只是磁盘或网络中的静态二进制格式。JVM 真正使用它时，会经历加载、验证、准备、解析、初始化等阶段。

从结构到运行时的关系可以简化为：

```text
.class 文件
  ├── 魔数和版本号：判断格式和兼容性
  ├── 常量池：提供符号引用和字面量
  ├── 类信息：确定当前类型和父类型
  ├── 接口信息：确定直接接口关系
  ├── 字段信息：确定对象和类变量布局所需的元数据
  ├── 方法信息：确定可调用方法及其字节码
  └── 属性信息：提供 Code、调试信息、注解、泛型签名等扩展内容

类加载过程
  ├── 加载：读取 Class 文件，生成运行时类元数据
  ├── 验证：检查格式、类型安全、字节码约束
  ├── 准备：为静态字段分配空间并设置默认值
  ├── 解析：把部分符号引用转换为直接引用
  └── 初始化：执行 <clinit>
```

常量池在运行时会进入运行时常量池。字节码里的 `#2`、`#3` 这类索引在类加载和解析过程中会逐步关联到具体的类、字段和方法。也就是说，Class 文件里保存的是平台无关的符号描述，JVM 在运行时把这些描述解析成可以执行的内部结构。

---

## 12. 逐字节读取示例

如果只看 `javap -v`，很容易把 Class 文件理解成“很多名字的集合”。更准确的理解方式是：它首先是一串字节，JVM 或工具按固定顺序逐字段读取。

下面用一个极简片段说明读取思路。假设一个 Class 文件开头字节如下：

```text
CA FE BA BE 00 00 00 34 00 0F 07 00 02 01 00 04 44 65 6D 6F ...
```

按 Class 文件顺序可以这样拆：

```text
CA FE BA BE          -> magic = 0xCAFEBABE
00 00                -> minor_version = 0
00 34                -> major_version = 52
00 0F                -> constant_pool_count = 15
07 00 02             -> #1 = CONSTANT_Class, name_index = 2
01 00 04 44 65 6D 6F -> #2 = CONSTANT_Utf8, length = 4, bytes = "Demo"
```

这说明：

- 前 4 字节先确认文件类型。
- 接着读版本号。
- 然后读常量池容量。
- 常量池第一个条目 `#1` 是 `CONSTANT_Class`，它自己不保存类名，只引用 `#2`。
- `#2` 是 `CONSTANT_Utf8`，才保存真实字符串 `Demo`。

如果继续向后读，就会遇到 `access_flags`、`this_class`、`super_class`、接口表、字段表、方法表和属性表。每一段都必须按照前面字段给出的数量来解释，不能靠“猜”。

这也是为什么 Class 文件能保持长期稳定：它不是自由格式文本，而是字段顺序和字段长度都被固定死的结构化二进制。

---

## 13. 小结

可以把 `.class` 文件理解为：

```text
魔数 + 版本号 + 常量池 + 类信息 + 接口信息 + 字段信息 + 方法信息 + 属性信息
```

其中最关键的是：

- **魔数和版本号**：说明这是 Class 文件，以及它需要什么版本的 JVM。
- **常量池**：保存类名、字段名、方法名、描述符和符号引用，是其他结构的索引中心。
- **类信息和接口信息**：描述当前类型、父类和直接接口。
- **字段表**：描述类中有哪些字段，以及字段的名称、类型、访问标志和字段属性。
- **方法表**：描述类中有哪些方法，包括构造方法 `<init>`、类初始化方法 `<clinit>` 和普通方法。
- **方法的 `Code` 属性**：保存真正要执行的 JVM 字节码。
- **属性表**：承载调试信息、泛型、注解、异常、内部类、动态调用等扩展信息。
- **逐字节解析**：所有结构最终都要落回具体字节、字段长度和常量池索引。

对于 `Demo` 这个例子，Class 文件最终记录了：

```text
Demo extends Object
private int value
public Demo()
public int add(int)
构造方法里执行 this.value = 10
add 方法里执行 return this.value + x
SourceFile 指向 Demo.java
LineNumberTable 记录源码行号和字节码偏移量的关系
```

源码中的类、字段和方法，最终都会被拆成这些结构化的二进制数据。JVM 先按固定格式解析这些数据，再通过常量池和属性表把符号、字节码、调试信息等内容组织成运行时可用的类元数据。
