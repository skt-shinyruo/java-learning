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

下面用一个覆盖面较广的 Java 8 示例观察 Class 文件结构。这个示例源码在 `jvm/src/main/java/yier/bubu/jvm/ClassFileTour.java`。它故意放进了泛型、接口、静态常量、实例字段初始化、静态初始化、可变参数、异常声明、注解、类型注解、lambda、匿名内部类和局部类。这样后面分析常量池、字段表、方法表和属性表时，可以尽量围绕同一个例子展开。

```java
package yier.bubu.jvm;

import java.io.Closeable;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

@RuntimeMark("class")
public class ClassFileTour<T extends Number> implements Runnable, Closeable {
    public static final int MAGIC = 7;
    private static final String PREFIX = "score:";
    private static int created;

    @RuntimeMark("field")
    private @TypeMark("generic-field") T value;
    private final List<String> history = new ArrayList<String>();

    static {
        created = MAGIC;
    }

    public ClassFileTour(T value) {
        this.value = value;
        this.history.add(PREFIX + value);
    }

    @RuntimeMark("method")
    public int compute(int base, String... tags) throws IOException {
        int total = base + value.intValue();
        for (String tag : tags) {
            total += tag.length();
        }

        final int snapshot = total;

        IntSupplier task = () -> snapshot + history.size();

        Runnable printer = new Runnable() {
            @Override
            public void run() {
                System.out.println(PREFIX + value);
            }
        };

        class LocalFormatter {
            private final int number;

            LocalFormatter(int number) {
                this.number = number;
            }

            String format() {
                return PREFIX + number;
            }
        }

        history.add(new LocalFormatter(total).format());
        printer.run();
        return task.getAsInt();
    }

    public int guardedLength(String text) {
        try {
            return text.length();
        } catch (NullPointerException e) {
            return -1;
        } finally {
            history.add("guarded");
        }
    }

    public static ClassFileTour<Integer> of(int value) {
        return new ClassFileTour<Integer>(value);
    }

    @Override
    public void run() {
        history.add("run");
    }

    @Override
    public void close() throws IOException {
        history.clear();
    }
}

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.TYPE_USE})
@interface RuntimeMark {
    String value();

    int level() default 1;
}

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
@interface TypeMark {
    String value();
}
```

编译并查看字节码：

```bash
mvn -pl jvm -am -DskipTests package
javap -v -p -classpath jvm/target/classes yier.bubu.jvm.ClassFileTour
```

如果要观察匿名内部类和局部类对应的 Class 文件，还可以继续看：

```bash
javap -v -p -classpath jvm/target/classes 'yier.bubu.jvm.ClassFileTour$1'
javap -v -p -classpath jvm/target/classes 'yier.bubu.jvm.ClassFileTour$1LocalFormatter'
```

如果使用 Java 8 目标版本编译，Class 文件主版本号是 `52`。不同 JDK 编译器、不同编译参数会让输出略有差异，例如常量池编号、是否包含 `LocalVariableTable`、是否生成调试行号信息，但核心结构不会变。

这个例子的源码会被拆成几类信息：

```text
ClassFileTour
  ├── 类信息：public class ClassFileTour<T extends Number>
  │     ├── extends java/lang/Object
  │     └── implements Runnable, Closeable
  ├── 字段信息：MAGIC、PREFIX、created、value、history
  │     ├── MAGIC / PREFIX 有 ConstantValue
  │     ├── value / history 有 Signature 保存泛型
  │     └── value 上有运行时可见注解和类型注解
  ├── 构造方法：public ClassFileTour(T)
  │     ├── 调用 Object.<init>()
  │     ├── 执行 history = new ArrayList<String>()
  │     ├── 执行 this.value = value
  │     └── 执行 history.add(PREFIX + value)
  ├── 类初始化方法：static {}
  │     └── 编译成 <clinit>，执行 created = MAGIC
  ├── 普通方法：compute、guardedLength、of、run、close
  │     ├── compute 有 ACC_VARARGS、Exceptions、Code、StackMapTable
  │     ├── guardedLength 有 try-catch-finally 对应的异常表
  │     ├── lambda 通过 invokedynamic 和 BootstrapMethods 连接
  │     └── 匿名内部类、局部类会生成额外的 .class 文件
  └── 属性信息：SourceFile、Signature、RuntimeVisibleAnnotations、
                RuntimeVisibleTypeAnnotations、BootstrapMethods、InnerClasses 等
```

需要注意：源码里的 `private final List<String> history = new ArrayList<String>();` 看起来像字段的一部分，但实例字段初始化不是直接保存在字段表里，而是编译进构造方法的字节码。字段表只描述“有什么字段”，方法的 `Code` 属性才描述“运行时做什么”。

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

Java 9 以后又增加了模块相关常量，例如 `CONSTANT_Module` 和 `CONSTANT_Package`；Java 11 又增加了 `CONSTANT_Dynamic`。本仓库以 Java 8 学习为主，所以正文重点放在 Java 8 会直接遇到的结构上。注解类型自身、枚举、接口、抽象类、真实异常表和 Java 9+ 扩展结构见 [Class 文件高级结构](class-file-advanced-structures.md)。

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
#2 = Class #4
#4 = Utf8  yier/bubu/jvm/ClassFileTour
```

`#2` 本身不保存 `ClassFileTour` 这些字符，它只保存 `name_index = 4`，再由 `#4` 保存真正的内部名文本 `yier/bubu/jvm/ClassFileTour`。

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
#1 = Fieldref    #2.#3  // yier/bubu/jvm/ClassFileTour.value:Ljava/lang/Number;
#2 = Class       #4     // yier/bubu/jvm/ClassFileTour
#3 = NameAndType #5:#6  // value:Ljava/lang/Number;
#5 = Utf8        value
#6 = Utf8        Ljava/lang/Number;
```

这说明 `#1` 不是一个平铺字符串，而是一组引用关系：

```text
Fieldref #1
  ├── class_index         -> #2 -> #4 -> yier/bubu/jvm/ClassFileTour
  └── name_and_type_index -> #3
        ├── name_index       -> #5 -> value
        └── descriptor_index -> #6 -> Ljava/lang/Number;
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

例如字段 `value:Ljava/lang/Number;` 和方法 `compute:(I[Ljava/lang/String;)I` 都可以用 `NameAndType` 表示：

```text
NameAndType
  ├── name_index       -> value
  └── descriptor_index -> Ljava/lang/Number;

NameAndType
  ├── name_index       -> compute
  └── descriptor_index -> (I[Ljava/lang/String;)I
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

`CONSTANT_MethodHandle`、`CONSTANT_MethodType` 和 `CONSTANT_InvokeDynamic` 主要服务于动态调用、lambda 和方法句柄。运行时 API 与链接过程见 [MethodHandle 与 invokedynamic](method-handle-invokedynamic.md)：

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

继续看 `ClassFileTour.class` 的常量池。下面只截取关键条目，真实输出里还有很多 `Utf8`、`NameAndType`、属性名和调试信息条目。不同 JDK 版本生成的条目编号可能略有差异，但组织方式一致。

```text
Constant pool:
 #1 = Fieldref           #2.#3    // yier/bubu/jvm/ClassFileTour.value:Ljava/lang/Number;
 #2 = Class              #4       // yier/bubu/jvm/ClassFileTour
 #3 = NameAndType        #5:#6    // value:Ljava/lang/Number;
 #7 = Methodref          #8.#9    // java/lang/Object."<init>":()V
#16 = Fieldref           #2.#17   // yier/bubu/jvm/ClassFileTour.history:Ljava/util/List;
#23 = String             #24      // score:
#36 = InterfaceMethodref #37.#38  // java/util/List.add:(Ljava/lang/Object;)Z
#42 = Methodref          #43.#44  // java/lang/Number.intValue:()I
#53 = InvokeDynamic      #0:#54   // #0:getAsInt:(Lyier/bubu/jvm/ClassFileTour;I)Ljava/util/function/IntSupplier;
#57 = Class              #58      // yier/bubu/jvm/ClassFileTour$1
#62 = Class              #63      // yier/bubu/jvm/ClassFileTour$1LocalFormatter
#75 = InterfaceMethodref #76.#77  // java/util/function/IntSupplier.getAsInt:()I
#99 = Fieldref           #2.#100  // yier/bubu/jvm/ClassFileTour.created:I
```

先看字符串字面量：

```text
#23 = String #24
#24 = Utf8   score:
```

`#23` 是 `CONSTANT_String`，它并不直接保存字符内容，而是引用 `#24`。`#24` 是 `CONSTANT_Utf8`，保存文本 `score:`。

再看字段引用：

```text
#16 = Fieldref    #2.#17
#2  = Class       #4       // yier/bubu/jvm/ClassFileTour
#17 = NameAndType #18:#19  // history:Ljava/util/List;
#18 = Utf8        history
#19 = Utf8        Ljava/util/List;
```

`#16` 表示字段 `yier.bubu.jvm.ClassFileTour.history`，它由两部分组成：

- 字段属于哪个类：`yier/bubu/jvm/ClassFileTour`
- 字段名和字段描述符：`history:Ljava/util/List;`

再看方法引用：

```text
#42 = Methodref   #43.#44
#43 = Class       #45      // java/lang/Number
#44 = NameAndType #46:#47  // intValue:()I
#46 = Utf8        intValue
#47 = Utf8        ()I
```

`#42` 表示方法 `java.lang.Number.intValue()`，其中：

- 方法所属类来自 `#43`。
- 方法名 `intValue` 来自 `#46`。
- 方法描述符 `()I` 来自 `#47`。

这个例子还出现了接口方法引用和动态调用点：

```text
#36 = InterfaceMethodref #37.#38  // java/util/List.add:(Ljava/lang/Object;)Z
#53 = InvokeDynamic      #0:#54   // #0:getAsInt:(Lyier/bubu/jvm/ClassFileTour;I)Ljava/util/function/IntSupplier;
```

`InterfaceMethodref` 用于接口方法调用，例如 `List.add` 和 `IntSupplier.getAsInt`。`InvokeDynamic` 用于 lambda 表达式；它不会直接写死一个普通目标方法，而是通过 `BootstrapMethods` 属性找到引导方法，再在运行时创建调用点。

### 4.5 字节码怎么引用 Class 文件常量池

同一个 `javap -v` 输出里，还能看到构造方法和 `compute` 方法的字节码。构造方法中有一段：

```text
20: aload_0
21: getfield      #16  // Field history:Ljava/util/List;
24: new           #20  // class java/lang/StringBuilder
31: ldc           #23  // String score:
43: invokeinterface #36,  2  // InterfaceMethod java/util/List.add:(Ljava/lang/Object;)Z
```

逐条看：

```text
ldc #23
```

表示从常量池第 `#23` 项加载常量，也就是字符串字面量 `"score:"`。

```text
getfield #16
```

表示读取常量池第 `#16` 项描述的实例字段，也就是：

```java
this.history
```

```text
invokeinterface #36
```

表示调用常量池第 `#36` 项描述的接口方法，也就是：

```java
java.util.List.add(Object)
```

`compute` 方法中的 lambda 会生成 `invokedynamic`：

```text
52: aload_0
53: iload         4
55: invokedynamic #53,  0
    // InvokeDynamic #0:getAsInt:(Lyier/bubu/jvm/ClassFileTour;I)Ljava/util/function/IntSupplier;
```

所以这个例子的关系可以整理成：

```text
ClassFileTour.class
  └── constant_pool
        ├── #23 -> "score:"
        ├── #16 -> yier.bubu.jvm.ClassFileTour.history
        ├── #36 -> List.add(Object)
        ├── #42 -> Number.intValue()
        └── #53 -> lambda 调用点

构造方法和 compute 方法字节码
  ├── ldc #23
  ├── getfield #16
  ├── invokevirtual #42
  ├── invokeinterface #36
  └── invokedynamic #53
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

`ClassFileTour` 的类信息大致是：

```text
public class yier.bubu.jvm.ClassFileTour
  flags: ACC_PUBLIC, ACC_SUPER
  this_class: #2   // yier/bubu/jvm/ClassFileTour
  super_class: #8  // java/lang/Object
```

这三个字段共同回答三个问题：

```text
access_flags -> 这个类型是什么形态：class / interface / annotation / enum / abstract / final
this_class   -> 当前类型的内部名是什么
super_class  -> 直接父类是谁
```

源码中的：

```java
public class ClassFileTour<T extends Number> implements Runnable, Closeable
```

在 Class 文件里会明确记录成：

```text
public class ClassFileTour<T extends Number>
  extends java.lang.Object
  implements java.lang.Runnable, java.io.Closeable
```

即使源码没有写 `extends Object`，`super_class` 也会指向 `java/lang/Object`。接口关系不放在 `super_class` 里，而是放在紧随其后的接口表里。只有 `java/lang/Object` 自己比较特殊，它没有父类，所以它的 `super_class` 为 `0`。

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

`access_flags` 是按位组合的。例如普通 `public class ClassFileTour` 常见值是：

```text
ACC_PUBLIC | ACC_SUPER = 0x0001 | 0x0020 = 0x0021
```

不同类型的标志组合不同：

```text
public class Example
  -> ACC_PUBLIC, ACC_SUPER

public final class FinalExample
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
this_class  -> CONSTANT_Class -> CONSTANT_Utf8 -> ClassFileTour
super_class -> CONSTANT_Class -> CONSTANT_Utf8 -> java/lang/Object
```

如果看原始内部名，`this_class` 实际会解析成 `yier/bubu/jvm/ClassFileTour`。`javap` 展示类声明时会把它还原成源码风格的点号包名 `yier.bubu.jvm.ClassFileTour`。

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

主示例中的源码：

```java
public class ClassFileTour<T extends Number> implements Runnable, Closeable
```

接口表可以简化理解为：

```text
interfaces_count: 2
interfaces[0]: #x  // java/lang/Runnable
interfaces[1]: #y  // java/io/Closeable
```

解析链路和 `this_class` 类似：

```text
interfaces[0] -> CONSTANT_Class -> CONSTANT_Utf8 -> java/lang/Runnable
interfaces[1] -> CONSTANT_Class -> CONSTANT_Utf8 -> java/io/Closeable
```

接口表有几个重要边界。

第一，它只记录直接接口，不递归展开父接口。比如：

```java
interface A {}
interface B extends A {}
class Example implements B {}
```

`Example.class` 的接口表只需要记录 `B`。`A` 是 `B.class` 自己的父接口关系，不会被复制到 `Example.class` 里。

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
public static final int MAGIC = 7;
private static final String PREFIX = "score:";
private static int created;
private @TypeMark("generic-field") T value;
private final List<String> history = new ArrayList<String>();
```

字段表中主要记录字段名、字段类型和访问标志：

```text
public static final int MAGIC;
  descriptor: I
  flags: ACC_PUBLIC, ACC_STATIC, ACC_FINAL
  ConstantValue: int 7

private T value;
  descriptor: Ljava/lang/Number;
  flags: ACC_PRIVATE
  Signature: TT;

private final java.util.List<java.lang.String> history;
  descriptor: Ljava/util/List;
  flags: ACC_PRIVATE, ACC_FINAL
  Signature: Ljava/util/List<Ljava/lang/String;>;
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
- `descriptor_index` 指向常量池中的字段描述符，例如 `Ljava/lang/Number;`。
- `attributes_count` 和 `attributes` 保存字段级属性，例如 `ConstantValue`、`Signature`、注解属性。

对 `private T value` 来说，字段表解析链路可以理解为：

```text
field_info
  ├── access_flags      -> ACC_PRIVATE
  ├── name_index        -> CONSTANT_Utf8 -> value
  ├── descriptor_index  -> CONSTANT_Utf8 -> Ljava/lang/Number;
  └── attributes        -> Signature / RuntimeVisibleAnnotations /
                           RuntimeVisibleTypeAnnotations
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

注意：`history = new ArrayList<String>()` 这段实例字段初始化逻辑通常不直接放在字段结构里，而是被编译进构造方法的 `Code` 属性中。字段表描述的是字段本身，不描述每次创建对象时如何给字段赋值。

这点可以用两类字段区分：

```java
private final List<String> history = new ArrayList<String>();
private static int count = initCount();
public static final int SIZE = 10;
```

它们在 Class 文件中的落点不同：

```text
history
  -> 字段表记录 private final List history
  -> 泛型 List<String> 记录在 Signature 属性里
  -> this.history = new ArrayList() 编译进 <init> 的 Code

count
  -> 字段表记录 private static int count
  -> count = initCount() 编译进 <clinit> 的 Code

SIZE
  -> 字段表记录 public static final int SIZE
  -> 编译期常量值 10 可以放进 ConstantValue 属性
```

主示例里的 `MAGIC` 和 `PREFIX` 都是静态 `final` 编译期常量，所以字段表里能看到 `ConstantValue`。而 `created = MAGIC` 来自 `static {}`，它不是字段属性，而是进入 `<clinit>` 的字节码。

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
  ├── name_index        -> CONSTANT_Utf8 -> compute
  ├── descriptor_index  -> CONSTANT_Utf8 -> (I[Ljava/lang/String;)I
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
int compute(int, String...)   -> (I[Ljava/lang/String;)I
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

构造方法在 Class 文件中不是名为 `ClassFileTour` 的普通方法，而是名为 `<init>` 的特殊方法。主示例中的构造方法：

```java
public ClassFileTour(T value) {
    this.value = value;
    this.history.add(PREFIX + value);
}
```

对应字节码大致是：

```text
public ClassFileTour(T);
  descriptor: (Ljava/lang/Number;)V
  flags: ACC_PUBLIC
  Code:
    0: aload_0
    1: invokespecial #7   // Method java/lang/Object."<init>":()V
    4: aload_0
    5: new           #13  // class java/util/ArrayList
    8: dup
    9: invokespecial #15  // Method java/util/ArrayList."<init>":()V
   12: putfield      #16  // Field history:Ljava/util/List;
   15: aload_0
   16: aload_1
   17: putfield      #1   // Field value:Ljava/lang/Number;
   20: aload_0
   21: getfield      #16  // Field history:Ljava/util/List;
   31: ldc           #23  // String score:
   43: invokeinterface #36,  2  // InterfaceMethod java/util/List.add:(Ljava/lang/Object;)Z
   49: return
```

逐条看：

- `aload_0`：把局部变量表第 `0` 个槽位里的 `this` 加载到操作数栈。
- `invokespecial #7`：调用父类构造方法，也就是 `super()`。
- `new #13`、`dup`、`invokespecial #15`：创建 `ArrayList` 并调用它的构造方法。
- `putfield #16`：给 `this.history` 赋值。
- `putfield #1`：给 `this.value` 赋值。
- `invokeinterface #36`：调用 `List.add(Object)`。
- `return`：构造方法返回，构造方法返回类型固定是 `void`。

方法描述符 `(Ljava/lang/Number;)V` 的意思是：

```text
(Ljava/lang/Number;)V
│                  │
│                  └── 返回 void
└───────────────────── 一个 Number 参数
```

因为 `T extends Number` 会被擦除成 `Number`，所以构造方法的描述符不是 `(TT;)V`。泛型形式会放在方法级 `Signature` 属性中，例如 `(TT;)V`。

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

主示例里有：

```java
static {
    created = MAGIC;
}
```

对应的 `<clinit>` 很短：

```text
static {};
  descriptor: ()V
  flags: ACC_STATIC
  Code:
    0: bipush        7
    2: putstatic     #99  // Field created:I
    5: return
```

`MAGIC` 自己是 `public static final int` 编译期常量，因此字段表中带 `ConstantValue: int 7`。`created` 不是 `final` 编译期常量，赋值逻辑进入了 `<clinit>`。

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

主示例里也能看到两类合成方法：

```text
private int lambda$compute$0(int);
  flags: ACC_PRIVATE, ACC_SYNTHETIC

static java.lang.Number access$000(yier.bubu.jvm.ClassFileTour);
  flags: ACC_STATIC, ACC_SYNTHETIC
```

`lambda$compute$0` 是 lambda 表达式的辅助方法，`invokedynamic` 会通过 `BootstrapMethods` 间接关联到它。`access$000` 是 Java 8 编译器为了让匿名内部类读取外部类私有字段 `value` 而生成的访问辅助方法；较新的 Java 编译策略可能不同，所以学习时要关注“合成方法用于弥补源码语义和字节码访问规则之间的差距”这一点。

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
public int compute(int base, String... tags) throws IOException {
    int total = base + value.intValue();
    for (String tag : tags) {
        total += tag.length();
    }

    final int snapshot = total;
    IntSupplier task = () -> snapshot + history.size();
    Runnable printer = new Runnable() {
        @Override
        public void run() {
            System.out.println(PREFIX + value);
        }
    };

    class LocalFormatter {
        private final int number;

        LocalFormatter(int number) {
            this.number = number;
        }

        String format() {
            return PREFIX + number;
        }
    }

    history.add(new LocalFormatter(total).format());
    printer.run();
    return task.getAsInt();
}
```

`method_info` 中会记录方法声明：

```text
public int compute(int, java.lang.String...) throws java.io.IOException;
  descriptor: (I[Ljava/lang/String;)I
  flags: ACC_PUBLIC, ACC_VARARGS
  Exceptions:
    throws java.io.IOException
```

真正的执行逻辑在它的 `Code` 属性里：

```text
Code:
  stack=5, locals=8, args_size=3
     0: iload_1
     1: aload_0
     2: getfield      #1   // Field value:Ljava/lang/Number;
     5: invokevirtual #42  // Method java/lang/Number.intValue:()I
     8: iadd
     9: istore_3
    21: iload         6
    23: iload         5
    25: if_icmpge     49
    35: iload_3
    36: aload         7
    38: invokevirtual #48  // Method java/lang/String.length:()I
    41: iadd
    42: istore_3
    43: iinc          6, 1
    46: goto          21
    55: invokedynamic #53,  0
    62: new           #57  // class yier/bubu/jvm/ClassFileTour$1
    76: new           #62  // class yier/bubu/jvm/ClassFileTour$1LocalFormatter
    88: invokeinterface #36,  2  // InterfaceMethod java/util/List.add:(Ljava/lang/Object;)Z
   103: invokeinterface #75,  1  // InterfaceMethod java/util/function/IntSupplier.getAsInt:()I
   108: ireturn
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
0: iload_1
1: aload_0
2: getfield #1
5: invokevirtual #42
8: iadd
9: istore_3
```

这里的 `0`、`1`、`2`、`5`、`8`、`9` 是字节码偏移量，不是源码行号。`getfield #1` 和 `invokevirtual #42` 都占用多个字节，所以后一条指令不会简单地按 `+1` 递增。

### 9.2 操作数栈和局部变量表

JVM 执行 Java 方法时，会为每一次方法调用创建一个栈帧。栈帧是当前方法的运行时工作区，属于线程私有的 JVM 栈；方法调用时入栈，方法返回或异常退出时出栈。

![线程 JVM 栈和当前栈帧结构](images/jvm-stack-frame-structure.svg)

图示按 JVM 规范层面的执行模型表达。具体到 HotSpot/JIT 时，局部变量和操作数栈的值可能被映射到本地栈槽、CPU 寄存器，甚至因为内联而不再物理创建某个被调用方法的栈帧；但字节码验证、异常处理、调试和去优化仍需要能恢复等价的 JVM 栈帧状态。

`max_locals` 决定这个方法的局部变量表最多需要多少个槽位，`max_stack` 决定操作数栈最多能压入多深。它们都写在方法的 `Code` 属性中。运行时的 `istore`、`astore` 这类指令不是把值写回 `.class` 文件，而是把当前栈帧里操作数栈顶的值弹出，保存到当前栈帧的局部变量表槽位中。

`max_stack` 之所以能在编译期算出来，是因为每条字节码指令对操作数栈的影响是固定的。比如 `iadd` 永远是“弹出 2 个 `int`，压回 1 个 `int`”；`invokevirtual`、`invokeinterface`、`invokespecial`、`invokestatic` 这类调用指令则由方法描述符固定参数和返回值形状。编译器只要沿控制流图做一次抽象执行，取所有路径上的最大栈深，就能得到 `max_stack`。更完整的调用说明见 [JVM 方法调用与返回指令](method-invocation-and-return.md)。

```text
iload / aload / fload ...
  -> 局部变量表槽位中的值复制到操作数栈

istore / astore / fstore ...
  -> 操作数栈顶的值弹出，保存到局部变量表槽位
```

JVM 字节码大多围绕操作数栈执行。`compute` 方法开头的 `base + value.intValue()` 可以按栈变化理解：

```text
iload_1
  -> 把局部变量槽位 1 的 int 参数 base 压栈

aload_0
  -> 把 this 压栈

getfield #1
  -> 弹出 this，读取 value，把 Number 引用压栈

invokevirtual #42
  -> 调用 Number.intValue()，弹出 Number 引用，压入 int 返回值

iadd
  -> 弹出两个 int，相加，把结果压栈

istore_3
  -> 把 int 结果保存到局部变量槽位 3，也就是 total
```

如果调用时 `base = 10`，并且 `value.intValue()` 返回 `7`，这一小段执行过程可以简化成：

![局部变量表和操作数栈变化过程](images/operand-stack-local-variables-flow.svg)

对实例方法 `compute(int base, String... tags)` 来说，局部变量表开头的槽位是：

```text
slot 0 -> this
slot 1 -> base
slot 2 -> tags
slot 3 -> total
```

所以字节码使用 `aload_0` 读取 `this`，使用 `iload_1` 读取参数 `base`，使用 `aload_2` 读取 `tags`。如果是静态方法，就没有隐式的 `this`，第一个参数会从 `slot 0` 开始。

`compute` 的 `max_stack=5`、`max_locals=8` 比简单加法大得多，是因为它包含循环、lambda、匿名内部类、局部类对象创建和多个局部变量。`javap` 里的 `LocalVariableTable` 能看到局部变量名、作用范围和槽位，例如 `total`、`snapshot`、`task`、`printer`。

局部变量和方法参数的 `final` 修饰符不会在局部变量槽位上留下“只读”标记。源码中的 `final int var = 0;` 不能再次赋值，是 Javac 在数据及控制流分析阶段拒绝了这种写法；到了字节码层面，只要类型、操作数栈和控制流满足验证规则，`istore`、`astore` 等指令仍然可以写入同一个局部变量槽位。换句话说，绕过 Javac 直接生成合法字节码时，JVM 验证器通常不会因为某个槽位在 Java 源码里曾经是 `final` 局部变量而拒绝 Class 文件。

这个结论不能推广到字段。字段表有自己的 `access_flags`，`final` 字段会以 `ACC_FINAL` 记录在 Class 文件中，并牵涉构造器、类初始化、反射、JIT 常量折叠和 Java 内存模型等额外语义。

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

`Code` 结构里的异常表描述异常处理范围。它和方法声明上的 `throws IOException` 不是一回事：`throws` 会进入方法级 `Exceptions` 属性，而 `try-catch-finally` 才会在 `Code` 内部产生异常表。异常表的每一项通常包括：

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

主示例的 `compute` 声明了 `throws IOException`，所以方法级属性中能看到：

```text
Exceptions:
  throws java.io.IOException
```

但 `compute` 方法体里没有 `try-catch`，所以它的 `Code` 异常表可以为空。这个区别很重要：`Exceptions` 属性描述方法签名上的受检异常声明；`Code.exception_table` 描述运行时异常处理跳转。

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
SourceFile: "ClassFileTour.java"
LineNumberTable:
  line 32: 0
  line 33: 10
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

`SourceFile` 只保存一个源文件名索引，例如 `ClassFileTour.java`。它不是源码本身，只是让堆栈、调试器和工具知道这个类来源于哪个文件：

```text
SourceFile_attribute {
    u2 attribute_name_index;
    u4 attribute_length;   // 固定为 2
    u2 sourcefile_index;   // 指向 CONSTANT_Utf8
}
```

主示例中可以看到：

```text
SourceFile: "ClassFileTour.java"
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
public static final int MAGIC = 7;
private static final String PREFIX = "score:";
```

编译后可以直接把 `7` 和 `"score:"` 放在 `ConstantValue` 里，这样类初始化时就不需要再执行字节码去给这两个字段赋值。

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
public int compute(int base, String... tags) throws IOException
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

主示例里有多个 `Signature`：

```text
Signature: <T:Ljava/lang/Number;>Ljava/lang/Object;Ljava/lang/Runnable;Ljava/io/Closeable;
Signature: TT;
Signature: Ljava/util/List<Ljava/lang/String;>;
Signature: (I)Lyier/bubu/jvm/ClassFileTour<Ljava/lang/Integer;>;
```

这些签名分别用于类、字段和方法。没有它们，描述符只能看到擦除后的 `Number`、`List`、`ClassFileTour`。

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

如果继续查看主示例生成的内部类文件，可以看到匿名内部类和局部类都有 `EnclosingMethod`：

```text
ClassFileTour$1.class
  EnclosingMethod: ClassFileTour.compute

ClassFileTour$1LocalFormatter.class
  EnclosingMethod: ClassFileTour.compute
```

`ClassFileTour.class` 自身则通过 `InnerClasses` 记录这些内部类的存在：

```text
InnerClasses:
  #57;                 // class yier/bubu/jvm/ClassFileTour$1
  LocalFormatter=...   // class yier/bubu/jvm/ClassFileTour$1LocalFormatter
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

主示例的 lambda：

```java
IntSupplier task = () -> snapshot + history.size();
```

会在 `compute` 方法里出现：

```text
invokedynamic #53,  0
```

类级属性里同时出现：

```text
BootstrapMethods:
  0: REF_invokeStatic java/lang/invoke/LambdaMetafactory.metafactory
    Method arguments:
      ()I
      REF_invokeSpecial yier/bubu/jvm/ClassFileTour.lambda$compute$0:(I)I
      ()I
```

这说明 lambda 的调用点和 `lambda$compute$0` 辅助方法不是靠普通 `invokevirtual` 直接绑定，而是由 `invokedynamic` 加 `BootstrapMethods` 共同描述。

`RuntimeVisibleAnnotations` 和 `RuntimeInvisibleAnnotations` 分别保存运行时可见和不可见的注解。前者会被反射 API 看到，后者通常只供编译器或工具使用。

`RuntimeVisibleTypeAnnotations` 和 `RuntimeInvisibleTypeAnnotations` 是更细一层的类型注解属性。它们描述的是“类型使用位置上的注解”，例如泛型参数、强转、`throws`、`instanceof` 等位置上的注解。

主示例中的：

```java
@RuntimeMark("field")
private @TypeMark("generic-field") T value;
```

会让字段同时带有普通注解属性和类型注解属性。普通注解标在字段声明上；类型注解标在字段类型使用位置上，所以 `javap -v -p` 会同时显示 `RuntimeVisibleAnnotations` 和 `RuntimeVisibleTypeAnnotations`。

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

下面用 `ClassFileTour.class` 的文件头片段说明读取思路。它开头的字节类似：

```text
CA FE BA BE 00 00 00 34 00 AE 09 00 02 00 03 07
00 04 0C 00 05 00 06 01 00 1B 79 69 65 72 2F 62 ...
```

按 Class 文件顺序可以这样拆：

```text
CA FE BA BE                      -> magic = 0xCAFEBABE
00 00                            -> minor_version = 0
00 34                            -> major_version = 52
00 AE                            -> constant_pool_count = 174
09 00 02 00 03                   -> #1 = CONSTANT_Fieldref
                                     class_index = 2, name_and_type_index = 3
07 00 04                         -> #2 = CONSTANT_Class, name_index = 4
0C 00 05 00 06                   -> #3 = CONSTANT_NameAndType
                                     name_index = 5, descriptor_index = 6
01 00 1B 79 69 65 72 2F 62 ...   -> #4 = CONSTANT_Utf8, length = 27,
                                     bytes = "yier/bubu/jvm/ClassFileTour"
```

这说明：

- 前 4 字节先确认文件类型。
- 接着读版本号。
- 然后读常量池容量。
- 常量池第一个条目 `#1` 是 `CONSTANT_Fieldref`，表示 `yier/bubu/jvm/ClassFileTour.value:Ljava/lang/Number;`。
- `#1` 自己不保存字段名和字段类型，它引用 `#2` 和 `#3`。
- `#2` 是 `CONSTANT_Class`，再引用 `#4`。
- `#4` 是 `CONSTANT_Utf8`，才保存真实内部名字符串 `yier/bubu/jvm/ClassFileTour`。

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

对于 `ClassFileTour` 这个例子，Class 文件最终记录了：

```text
ClassFileTour<T extends Number> extends Object
implements Runnable, Closeable
public static final int MAGIC = 7
private static final String PREFIX = "score:"
private static int created
private T value
private final List<String> history
public ClassFileTour(T)
public int compute(int, String...) throws IOException
public int guardedLength(String)
public static ClassFileTour<Integer> of(int)
public void run()
public void close() throws IOException
<clinit> 执行 created = MAGIC
lambda 通过 invokedynamic 和 BootstrapMethods 描述
匿名内部类和局部类通过额外 .class 文件、InnerClasses、EnclosingMethod 描述
SourceFile 指向 ClassFileTour.java
LineNumberTable / LocalVariableTable / StackMapTable 记录调试和校验信息
```

源码中的类、字段和方法，最终都会被拆成这些结构化的二进制数据。JVM 先按固定格式解析这些数据，再通过常量池和属性表把符号、字节码、调试信息等内容组织成运行时可用的类元数据。
