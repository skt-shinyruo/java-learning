# Class 文件高级结构

这篇是 [Class 文件结构](class-file-structure.md) 的补充篇。主文档围绕一个普通 Java 8 class 展开，已经覆盖了魔数、版本号、常量池、字段表、方法表、`Code`、`Exceptions`、泛型签名、注解、类型注解、lambda、内部类和逐字节读取方式。

本文专门补齐几类容易漏掉的结构：

- 注解类型自己的 `.class` 文件，例如 `RuntimeMark.class` 和 `TypeMark.class`
- 接口、抽象类、枚举这些非普通 class 的独立结构
- `try-catch-finally` 对应的真实 `Code.exception_table`
- Java 9 之后加入的 `Module`、`Package`、`CONSTANT_Dynamic`、`Record`、`PermittedSubclasses`

下面仍然以 `javap -v -p` 输出为主。不同 JDK、不同编译参数会改变常量池编号和调试属性，但访问标志、属性类型和整体组织方式保持一致。

---

## 1. 准备命令

仓库里的主示例源码是：

```text
jvm/src/main/java/yier/bubu/jvm/ClassFileTour.java
```

编译并查看：

```bash
mvn -pl jvm -am -DskipTests package
javap -v -p -classpath jvm/target/classes yier.bubu.jvm.RuntimeMark
javap -v -p -classpath jvm/target/classes yier.bubu.jvm.TypeMark
javap -v -p -classpath jvm/target/classes yier.bubu.jvm.ClassFileTour
```

后面的接口、抽象类、枚举、模块、record、sealed 示例是独立小片段。它们的目的不是引入新业务代码，而是让每种 Class 文件形态都有最小可观察样本。

---

## 2. 注解类型本身的 Class 文件

主示例中有两个注解类型：

```java
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

`@RuntimeMark("class")` 这种写法是“注解使用处”。而 `@interface RuntimeMark` 自己也会被编译成一个独立 Class 文件。它不是普通接口的纯文本别名，而是有一组特定的访问标志和方法属性。

### 2.1 RuntimeMark.class

`RuntimeMark.class` 的头部类似：

```text
interface yier.bubu.jvm.RuntimeMark extends java.lang.annotation.Annotation
  minor version: 0
  major version: 52
  flags: (0x2600) ACC_INTERFACE, ACC_ABSTRACT, ACC_ANNOTATION
  this_class: #1                          // yier/bubu/jvm/RuntimeMark
  super_class: #3                         // java/lang/Object
  interfaces: 1, fields: 0, methods: 2, attributes: 2
```

这里有几个关键点：

- `@interface` 会变成接口形态，所以有 `ACC_INTERFACE` 和 `ACC_ABSTRACT`
- 注解类型额外有 `ACC_ANNOTATION`
- 源码没有写 `extends java.lang.annotation.Annotation`，但 Class 文件的 `interfaces` 表里会记录这个直接父接口
- `super_class` 仍然是 `java/lang/Object`，这是接口 Class 文件的固定组织方式之一

常量池里可以看到注解方法和默认值相关条目：

```text
#5  = Class   #6   // java/lang/annotation/Annotation
#7  = Utf8        value
#8  = Utf8        ()Ljava/lang/String;
#9  = Utf8        level
#10 = Utf8        ()I
#11 = Utf8        AnnotationDefault
#12 = Integer     1
```

注解的元素方法会出现在 `methods` 表里：

```text
public abstract java.lang.String value();
  descriptor: ()Ljava/lang/String;
  flags: (0x0401) ACC_PUBLIC, ACC_ABSTRACT

public abstract int level();
  descriptor: ()I
  flags: (0x0401) ACC_PUBLIC, ACC_ABSTRACT
  AnnotationDefault:
    default_value: I#12
      1
```

`String value();` 不是字段，而是一个没有 `Code` 属性的抽象方法。`int level() default 1;` 也是抽象方法，只是方法属性表里多了 `AnnotationDefault`。

`AnnotationDefault` 的核心含义是：这个注解元素在使用处没有显式赋值时，反射和注解解析器应该把默认值当作元素值。它属于具体的注解方法，而不是 Class 级属性。

### 2.2 元注解保存在 RuntimeVisibleAnnotations 中

`RuntimeMark` 自己被 `@Retention` 和 `@Target` 修饰。因为这两个元注解运行时可见，所以它们出现在 `RuntimeMark.class` 的 Class 级 `RuntimeVisibleAnnotations` 中：

```text
RuntimeVisibleAnnotations:
  0: #16(#7=e#17.#18)
    java.lang.annotation.Retention(
      value=Ljava/lang/annotation/RetentionPolicy;.RUNTIME
    )
  1: #19(#7=[e#20.#21,e#20.#22,e#20.#23,e#20.#24])
    java.lang.annotation.Target(
      value=[Ljava/lang/annotation/ElementType;.TYPE,
             Ljava/lang/annotation/ElementType;.FIELD,
             Ljava/lang/annotation/ElementType;.METHOD,
             Ljava/lang/annotation/ElementType;.TYPE_USE]
    )
```

这和 `ClassFileTour.class` 上的 `@RuntimeMark("class")` 是两层不同的东西：

| 位置 | Class 文件 | 含义 |
| --- | --- | --- |
| `@interface RuntimeMark` 本身 | `RuntimeMark.class` | 声明一个注解类型 |
| `@Retention`、`@Target` | `RuntimeMark.class` 的 `RuntimeVisibleAnnotations` | 描述这个注解类型如何被保留、允许修饰哪里 |
| `@RuntimeMark("class")` | `ClassFileTour.class` 的 `RuntimeVisibleAnnotations` | 普通 class 使用了这个注解 |

### 2.3 TypeMark.class

`TypeMark` 的结构更小：

```text
interface yier.bubu.jvm.TypeMark extends java.lang.annotation.Annotation
  major version: 52
  flags: (0x2600) ACC_INTERFACE, ACC_ABSTRACT, ACC_ANNOTATION
  interfaces: 1, fields: 0, methods: 1, attributes: 2

public abstract java.lang.String value();
  descriptor: ()Ljava/lang/String;
  flags: (0x0401) ACC_PUBLIC, ACC_ABSTRACT
```

它只有一个元素方法 `value()`，没有默认值，所以方法上没有 `AnnotationDefault`。它的 `@Target(ElementType.TYPE_USE)` 会影响使用处的编码：当它修饰字段类型、返回值类型、泛型实参、数组维度等类型位置时，使用处会进入 `RuntimeVisibleTypeAnnotations` 或 `RuntimeInvisibleTypeAnnotations`，而不是普通声明注解表。

---

## 3. 接口、抽象类、枚举的独立结构

普通 class 只是 Class 文件的一种形态。接口、抽象类和枚举仍然使用同一个 `ClassFile` 外壳，但访问标志、字段、方法和属性会呈现不同模式。

下面用一个 Java 8 小例子观察：

```java
package advanced;

interface AdvancedTask {
    int VERSION = 1;

    void run();

    default String name() {
        return "task";
    }

    static AdvancedTask noop() {
        return new AdvancedTask() {
            @Override
            public void run() {
            }
        };
    }
}

abstract class AbstractWorker implements AdvancedTask {
    protected abstract int cost();

    @Override
    public void run() {
        if (cost() > 0) {
            onRun();
        }
    }

    protected void onRun() {
    }
}

enum AdvancedState {
    NEW(0),
    DONE(1);

    private final int code;

    AdvancedState(int code) {
        this.code = code;
    }

    int code() {
        return code;
    }
}
```

可以用 Java 8 目标版本编译：

```bash
javac --release 8 -g -d /tmp/classfile-advanced/out AdvancedShapes.java
javap -v -p -classpath /tmp/classfile-advanced/out advanced.AdvancedTask
javap -v -p -classpath /tmp/classfile-advanced/out advanced.AbstractWorker
javap -v -p -classpath /tmp/classfile-advanced/out advanced.AdvancedState
```

### 3.1 接口

`AdvancedTask.class` 的头部：

```text
interface advanced.AdvancedTask
  minor version: 0
  major version: 52
  flags: (0x0600) ACC_INTERFACE, ACC_ABSTRACT
  super_class: #11                        // java/lang/Object
  interfaces: 0, fields: 1, methods: 3, attributes: 2
```

接口字段 `VERSION`：

```text
public static final int VERSION;
  descriptor: I
  flags: (0x0019) ACC_PUBLIC, ACC_STATIC, ACC_FINAL
  ConstantValue: int 1
```

接口里的字段天然是 `public static final`。即使源码只写 `int VERSION = 1;`，字段表中也会显式带上这些访问标志。

接口抽象方法没有 `Code`：

```text
public abstract void run();
  descriptor: ()V
  flags: (0x0401) ACC_PUBLIC, ACC_ABSTRACT
```

Java 8 的默认方法和静态方法则有真正的字节码：

```text
public default java.lang.String name();
  descriptor: ()Ljava/lang/String;
  flags: (0x0001) ACC_PUBLIC
  Code:
     0: ldc           #1                  // String task
     2: areturn

public static advanced.AdvancedTask noop();
  descriptor: ()Ladvanced/AdvancedTask;
  flags: (0x0009) ACC_PUBLIC, ACC_STATIC
  Code:
     0: new           #3                  // class advanced/AdvancedTask$1
     3: dup
     4: invokespecial #5                  // Method advanced/AdvancedTask$1."<init>":()V
     7: areturn
```

所以不能简单说“接口方法都没有 `Code`”。Java 8 之后，接口里的默认方法和静态方法都可以有 `Code` 属性。

### 3.2 抽象类

`AbstractWorker.class` 的头部：

```text
abstract class advanced.AbstractWorker implements advanced.AdvancedTask
  minor version: 0
  major version: 52
  flags: (0x0420) ACC_SUPER, ACC_ABSTRACT
  super_class: #2                         // java/lang/Object
  interfaces: 1, fields: 0, methods: 4, attributes: 1
```

抽象类的特点是 Class 访问标志里有 `ACC_ABSTRACT`。它可以同时包含抽象方法和普通方法：

```text
protected abstract int cost();
  descriptor: ()I
  flags: (0x0404) ACC_PROTECTED, ACC_ABSTRACT

public void run();
  descriptor: ()V
  flags: (0x0001) ACC_PUBLIC
  Code:
     0: aload_0
     1: invokevirtual #7                  // Method cost:()I
     4: ifle          11
     7: aload_0
     8: invokevirtual #13                 // Method onRun:()V
    11: return
```

`cost()` 没有 `Code`，因为它是抽象方法。`run()` 和 `onRun()` 有 `Code`，因为它们有方法体。抽象类仍然会有构造方法 `<init>`，用于执行父类构造和本类初始化逻辑。

### 3.3 枚举

`AdvancedState.class` 的头部：

```text
final class advanced.AdvancedState extends java.lang.Enum<advanced.AdvancedState>
  minor version: 0
  major version: 52
  flags: (0x4030) ACC_FINAL, ACC_SUPER, ACC_ENUM
  super_class: #20                        // java/lang/Enum
  interfaces: 0, fields: 4, methods: 6, attributes: 2
```

简单枚举会编译成一个继承 `java.lang.Enum` 的 final class，并带 `ACC_ENUM`。枚举常量是字段：

```text
public static final advanced.AdvancedState NEW;
  descriptor: Ladvanced/AdvancedState;
  flags: (0x4019) ACC_PUBLIC, ACC_STATIC, ACC_FINAL, ACC_ENUM

public static final advanced.AdvancedState DONE;
  descriptor: Ladvanced/AdvancedState;
  flags: (0x4019) ACC_PUBLIC, ACC_STATIC, ACC_FINAL, ACC_ENUM
```

编译器还会生成一个保存所有常量的数组字段：

```text
private static final advanced.AdvancedState[] $VALUES;
  descriptor: [Ladvanced/AdvancedState;
  flags: (0x101a) ACC_PRIVATE, ACC_STATIC, ACC_FINAL, ACC_SYNTHETIC
```

以及 `values()`、`valueOf(String)`：

```text
public static advanced.AdvancedState[] values();
  descriptor: ()[Ladvanced/AdvancedState;
  flags: (0x0009) ACC_PUBLIC, ACC_STATIC
  Code:
     0: getstatic     #10                 // Field $VALUES:[Ladvanced/AdvancedState;
     3: invokevirtual #14                 // Method "[Ladvanced/AdvancedState;".clone:()Ljava/lang/Object;
     6: checkcast     #15                 // class "[Ladvanced/AdvancedState;"
     9: areturn

public static advanced.AdvancedState valueOf(java.lang.String);
  descriptor: (Ljava/lang/String;)Ladvanced/AdvancedState;
  flags: (0x0009) ACC_PUBLIC, ACC_STATIC
  Code:
     0: ldc           #1                  // class advanced/AdvancedState
     2: aload_0
     3: invokestatic  #19                 // Method java/lang/Enum.valueOf:(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;
     6: checkcast     #1                  // class advanced/AdvancedState
     9: areturn
```

枚举构造方法也会被改写。源码里只有 `AdvancedState(int code)`，但字节码描述符是：

```text
private advanced.AdvancedState(int);
  descriptor: (Ljava/lang/String;II)V
  flags: (0x0002) ACC_PRIVATE
  Code:
     0: aload_0
     1: aload_1
     2: iload_2
     3: invokespecial #25                 // Method java/lang/Enum."<init>":(Ljava/lang/String;I)V
     6: aload_0
     7: iload_3
     8: putfield      #29                 // Field code:I
    11: return
  Signature: #51                          // (I)V
```

前两个隐藏参数是枚举常量名和 ordinal。`Signature: (I)V` 保留了源码视角下的构造器参数，真实 descriptor 则包含编译器添加的 `(String, int)`。

枚举常量实例在 `<clinit>` 里创建：

```text
static {};
  Code:
     0: new           #1                  // class advanced/AdvancedState
     3: dup
     4: ldc           #33                 // String NEW
     6: iconst_0
     7: iconst_0
     8: invokespecial #34                 // Method "<init>":(Ljava/lang/String;II)V
    11: putstatic     #3                  // Field NEW:Ladvanced/AdvancedState;
    14: new           #1                  // class advanced/AdvancedState
    17: dup
    18: ldc           #37                 // String DONE
    20: iconst_1
    21: iconst_1
    22: invokespecial #34                 // Method "<init>":(Ljava/lang/String;II)V
    25: putstatic     #7                  // Field DONE:Ladvanced/AdvancedState;
    28: invokestatic  #38                 // Method $values:()[Ladvanced/AdvancedState;
    31: putstatic     #10                 // Field $VALUES:[Ladvanced/AdvancedState;
    34: return
```

这说明枚举常量不是魔法值，而是在类初始化阶段创建出来的静态对象。

### 3.4 差异小结

| 类型 | Class 访问标志 | 字段特征 | 方法特征 | 常见属性 |
| --- | --- | --- | --- | --- |
| 普通 class | `ACC_SUPER`，可能有 `ACC_PUBLIC`、`ACC_FINAL` | 普通字段、静态字段、常量字段 | 构造器、普通方法、可能有 `<clinit>` | `SourceFile`、`Signature`、`InnerClasses`、注解 |
| 接口 | `ACC_INTERFACE`、`ACC_ABSTRACT` | 默认 `public static final` | 抽象方法无 `Code`；default/static 方法有 `Code` | `InnerClasses`、注解、泛型签名 |
| 注解 | `ACC_INTERFACE`、`ACC_ABSTRACT`、`ACC_ANNOTATION` | 通常无字段 | 注解元素是 `public abstract` 方法；默认值在 `AnnotationDefault` | `RuntimeVisibleAnnotations` 保存元注解 |
| 抽象类 | `ACC_ABSTRACT`，通常有 `ACC_SUPER` | 和普通 class 类似 | 抽象方法无 `Code`；普通方法有 `Code` | 和普通 class 类似 |
| 枚举 | `ACC_ENUM`，简单枚举常见 `ACC_FINAL` | 枚举常量字段带 `ACC_ENUM`；有 `$VALUES` | 生成 `values`、`valueOf`、`<clinit>`；构造器含隐藏参数 | `Signature`、`SourceFile` |

---

## 4. try-catch-finally 的真实异常表

主文档已经区分了 `Exceptions` 属性和 `Code.exception_table`：

- `Exceptions` 是方法表级属性，来自源码的 `throws` 声明
- `exception_table` 是 `Code` 属性内部的一部分，描述运行时异常处理区间

为了看到真实异常表，主示例中增加了一个短方法：

```java
public int guardedLength(String text) {
    try {
        return text.length();
    } catch (NullPointerException e) {
        return -1;
    } finally {
        history.add("guarded");
    }
}
```

`javap -v -p` 输出：

```text
public int guardedLength(java.lang.String);
  descriptor: (Ljava/lang/String;)I
  flags: (0x0001) ACC_PUBLIC
  Code:
    stack=2, locals=5, args_size=2
       0: aload_1
       1: invokevirtual #48                 // Method java/lang/String.length:()I
       4: istore_2
       5: aload_0
       6: getfield      #16                 // Field history:Ljava/util/List;
       9: ldc           #79                 // String guarded
      11: invokeinterface #36,  2           // InterfaceMethod java/util/List.add:(Ljava/lang/Object;)Z
      16: pop
      17: iload_2
      18: ireturn
      19: astore_2
      20: iconst_m1
      21: istore_3
      22: aload_0
      23: getfield      #16                 // Field history:Ljava/util/List;
      26: ldc           #79                 // String guarded
      28: invokeinterface #36,  2           // InterfaceMethod java/util/List.add:(Ljava/lang/Object;)Z
      33: pop
      34: iload_3
      35: ireturn
      36: astore        4
      38: aload_0
      39: getfield      #16                 // Field history:Ljava/util/List;
      42: ldc           #79                 // String guarded
      44: invokeinterface #36,  2           // InterfaceMethod java/util/List.add:(Ljava/lang/Object;)Z
      49: pop
      50: aload         4
      52: athrow
    Exception table:
       from    to  target type
           0     5    19   Class java/lang/NullPointerException
           0     5    36   any
          19    22    36   any
          36    38    36   any
```

异常表的每一行对应一个 `exception_table` 条目：

```text
exception_table_entry {
    u2 start_pc;
    u2 end_pc;
    u2 handler_pc;
    u2 catch_type;
}
```

解释这个方法时要注意四点：

- `from` 到 `to` 是半开区间 `[start_pc, end_pc)`，所以第一行保护的是 `0..4` 这段 `text.length()` 逻辑
- `target` 是处理器入口，例如第一行的 `19` 对应 `catch (NullPointerException e)`
- `type` 是捕获类型；`any` 表示 `catch_type = 0`，也就是捕获所有异常，常用于 `finally`
- `finally` 不一定是一个独立指令块。这里可以看到 `history.add("guarded")` 被复制到了正常返回、catch 返回、异常重抛三条路径中

第一行：

```text
0  5  19  Class java/lang/NullPointerException
```

表示 `text.length()` 如果抛出 `NullPointerException`，跳到 `19`，也就是执行 `catch` 分支。

第二行：

```text
0  5  36  any
```

表示 `try` 主体如果抛出其他异常，跳到 `36`，执行 `finally` 逻辑后再 `athrow`。

第三行：

```text
19  22  36  any
```

保护 `catch` 分支中计算返回值的短区间。如果 `catch` 分支在执行过程中又抛异常，也要先进入 `finally`。

因此，`Exceptions` 和 `exception_table` 的区别可以这样记：

```text
throws IOException
  -> method_info.attributes 里的 Exceptions
  -> 告诉调用者：这个方法声明可能抛出哪些 checked exception

try/catch/finally
  -> Code.attributes 内部的 exception_table
  -> 告诉 JVM：字节码执行到某个 PC 范围内抛异常时跳到哪里处理
```

`guardedLength` 没有 `throws`，所以它没有 `Exceptions` 属性；但它有 `try-catch-finally`，所以它的 `Code` 里有异常表。

---

## 5. Java 9+ 新结构

主文档以 Java 8 为主，因为仓库模块使用 Java 8 兼容目标。Java 9 以后，Class 文件继续保持同一个大框架，但新增了一些常量池项和属性。

| Java 版本 | Class 主版本 | 新结构示例 |
| --- | --- | --- |
| Java 8 | 52 | lambda 常见 `CONSTANT_InvokeDynamic`、`BootstrapMethods` |
| Java 9 | 53 | `CONSTANT_Module`、`CONSTANT_Package`、`Module` 属性 |
| Java 11 | 55 | `CONSTANT_Dynamic` |
| Java 14/15 | 58/59 | record 预览 |
| Java 16 | 60 | record 正式 |
| Java 17 | 61 | sealed class/interface 正式，`PermittedSubclasses` |

下面只解释 Class 文件层面的结构，不展开 Java 语言语义。

### 5.1 Module 和 Package

Java 9 模块系统会生成 `module-info.class`。示例：

```java
module demo.module {
    exports demo.mod;
}
```

搭配一个被导出的包：

```java
package demo.mod;

public class ModuleApi {
    public String name() {
        return "module";
    }
}
```

编译：

```bash
javac --release 9 -d /tmp/classfile-module/out module-info.java demo/mod/ModuleApi.java
javap -v -p /tmp/classfile-module/out/module-info.class
```

`module-info.class` 的头部：

```text
module demo.module
  minor version: 0
  major version: 53
  flags: (0x8000) ACC_MODULE
  this_class: #1                          // "module-info"
  super_class: #0
  interfaces: 0, fields: 0, methods: 0, attributes: 2
```

模块描述文件不是普通类：

- `ACC_MODULE` 表示这是模块描述符
- `super_class` 是 `0`
- 没有字段、方法、接口
- 模块信息主要放在 Class 级 `Module` 属性中

常量池中出现了 Java 9 新增的 `CONSTANT_Module` 和 `CONSTANT_Package`：

```text
#5  = Utf8     Module
#6  = Module   #7             // "demo.module"
#7  = Utf8     demo.module
#8  = Module   #9             // "java.base"
#9  = Utf8     java.base
#10 = Package  #11            // demo/mod
#11 = Utf8     demo/mod
```

`Module` 属性记录模块名、依赖、导出、开放、使用和提供的服务：

```text
Module:
  #6,0                                    // "demo.module"
  #0
  1                                       // requires
    #8,8000                               // "java.base" ACC_MANDATED
    #0
  1                                       // exports
    #10,0                                 // demo/mod
  0                                       // opens
  0                                       // uses
  0                                       // provides
```

这里的 `Package` 常量不是“所有普通 class 都会有的 package 声明”。它是模块属性为了描述 `exports demo.mod`、`opens ...` 等模块关系而引入的常量池项。

### 5.2 CONSTANT_Dynamic

Java 11 引入 `CONSTANT_Dynamic`，常量池标签值是 `17`。它的结构和 `CONSTANT_InvokeDynamic` 很像，都会引用 `BootstrapMethods`：

```text
CONSTANT_Dynamic_info {
    u1 tag;                              // 17
    u2 bootstrap_method_attr_index;
    u2 name_and_type_index;
}
```

概念上的 `javap` 形态类似：

```text
#12 = Dynamic #0:#34                     // SOME_VALUE:Ljava/lang/String;
#34 = NameAndType #35:#36                // SOME_VALUE:Ljava/lang/String;

BootstrapMethods:
  0: #45 REF_invokeStatic java/lang/invoke/ConstantBootstraps.xxx:(...)...
```

它和 `CONSTANT_InvokeDynamic` 的区别在于目标不同：

| 常量池项 | 服务对象 | 解析结果 |
| --- | --- | --- |
| `CONSTANT_InvokeDynamic` | `invokedynamic` 指令 | 一个动态调用点 `CallSite` |
| `CONSTANT_Dynamic` | `ldc`、注解元素值以外的动态常量位置、部分引导参数 | 一个动态计算出来的常量值 |

`CONSTANT_Dynamic` 让 Class 文件可以把“常量值”延迟到链接解析阶段，通过引导方法计算。它和 `invokedynamic`、`MethodHandle`、`CallSite` 的运行时关系见 [MethodHandle 与 invokedynamic](method-handle-invokedynamic.md)。普通 Java 源码不一定容易让 `javac` 直接生成它；很多时候它来自字节码生成工具、语言实现或 JDK 内部生成逻辑。阅读 Class 文件时，只要看到 `Dynamic` 条目，就要同时查看：

- 它的 `NameAndType`，确定常量名和类型描述符
- 它的 `bootstrap_method_attr_index`，到 `BootstrapMethods` 里找到引导方法
- 引导方法参数，因为真实常量值依赖这些参数计算

### 5.3 Record 属性

record 在 Java 14/15 是预览特性，在 Java 16 正式。使用 JDK 17 编译一个最小 record：

```java
package modern;

public record Point(int x, int y) {
}
```

编译查看：

```bash
javac --release 17 -d /tmp/classfile-modern/out modern/Point.java
javap -v -p -classpath /tmp/classfile-modern/out modern.Point
```

头部：

```text
public final class modern.Point extends java.lang.Record
  minor version: 0
  major version: 61
  flags: (0x0031) ACC_PUBLIC, ACC_FINAL, ACC_SUPER
  super_class: #2                         // java/lang/Record
  interfaces: 0, fields: 2, methods: 6, attributes: 4
```

record class 继承 `java.lang.Record`，并且有组件对应的私有 final 字段：

```text
private final int x;
  descriptor: I
  flags: (0x0012) ACC_PRIVATE, ACC_FINAL

private final int y;
  descriptor: I
  flags: (0x0012) ACC_PRIVATE, ACC_FINAL
```

构造方法和访问器都是普通方法表条目：

```text
public modern.Point(int, int);
  descriptor: (II)V
  flags: (0x0001) ACC_PUBLIC
  Code:
     0: aload_0
     1: invokespecial #1                  // Method java/lang/Record."<init>":()V
     4: aload_0
     5: iload_1
     6: putfield      #7                  // Field x:I
     9: aload_0
    10: iload_2
    11: putfield      #13                 // Field y:I
    14: return
  MethodParameters:
    Name                           Flags
    x
    y

public int x();
  descriptor: ()I
  flags: (0x0001) ACC_PUBLIC

public int y();
  descriptor: ()I
  flags: (0x0001) ACC_PUBLIC
```

record 特有信息在 Class 级 `Record` 属性中：

```text
Record:
  int x;
    descriptor: I

  int y;
    descriptor: I
```

`Record` 属性记录 record component 列表。每个 component 有名称、描述符和自己的属性表。组件上的泛型签名、注解、类型注解等信息会挂在对应 component 的属性表中。

JDK 17 编译器还会用 `invokedynamic` 和 `java.lang.runtime.ObjectMethods.bootstrap` 生成 `toString`、`hashCode`、`equals`：

```text
public final java.lang.String toString();
  Code:
     0: aload_0
     1: invokedynamic #16,  0             // InvokeDynamic #0:toString:(Lmodern/Point;)Ljava/lang/String;
     6: areturn

BootstrapMethods:
  0: #39 REF_invokeStatic java/lang/runtime/ObjectMethods.bootstrap:(...)Ljava/lang/Object;
    Method arguments:
      #8 modern/Point
      #46 x;y
      #48 REF_getField modern/Point.x:I
      #49 REF_getField modern/Point.y:I
```

所以 record 不是只靠字段和方法约定识别的。工具如果要准确识别 record，应查看 Class 级 `Record` 属性。

### 5.4 Sealed 与 PermittedSubclasses

Java 17 正式引入 sealed class/interface。示例：

```java
package modern;

public sealed interface Shape permits Circle, Rect {
}

final class Circle implements Shape {
}

final class Rect implements Shape {
}
```

编译查看：

```bash
javac --release 17 -d /tmp/classfile-modern/out modern/Shape.java
javap -v -p -classpath /tmp/classfile-modern/out modern.Shape
```

`Shape.class` 的头部：

```text
public interface modern.Shape
  minor version: 0
  major version: 61
  flags: (0x0601) ACC_PUBLIC, ACC_INTERFACE, ACC_ABSTRACT
  this_class: #1                          // modern/Shape
  super_class: #3                         // java/lang/Object
  interfaces: 0, fields: 0, methods: 0, attributes: 2
```

注意这里没有单独的 `ACC_SEALED`。sealed 关系由 Class 级 `PermittedSubclasses` 属性表达：

```text
PermittedSubclasses:
  modern/Circle
  modern/Rect
```

这个属性的结构可以理解成：

```text
PermittedSubclasses_attribute {
    u2 attribute_name_index;
    u4 attribute_length;
    u2 number_of_classes;
    u2 classes[number_of_classes];        // 每项都是 CONSTANT_Class 索引
}
```

实现类本身还是普通 final class：

```text
final class modern.Circle implements modern.Shape
  minor version: 0
  major version: 61
  flags: (0x0030) ACC_FINAL, ACC_SUPER
  interfaces: 1, fields: 0, methods: 1, attributes: 1
```

读取 sealed 类型时，关键不是只看实现类列表，而是回到 sealed 父类型的 `PermittedSubclasses` 属性。JVM 验证和语言工具都会以这个属性为准，判断哪些直接子类或实现类被允许。

---

## 6. 阅读路线

如果按 Class 文件解析顺序阅读，可以这样串起来：

```text
普通 class
  -> 先掌握主文档里的魔数、版本、常量池、字段表、方法表、属性表
  -> 再看 Code、异常表、StackMapTable、泛型签名、注解、内部类

注解类型
  -> 看 ACC_ANNOTATION
  -> 看 extends java.lang.annotation.Annotation
  -> 看注解元素方法和 AnnotationDefault
  -> 看元注解如何进入 RuntimeVisibleAnnotations

接口、抽象类、枚举
  -> 看 class access_flags
  -> 看哪些方法有 Code，哪些方法没有 Code
  -> 看枚举常量、$VALUES、values/valueOf、<clinit>

Java 9+
  -> module-info.class 看 ACC_MODULE、Module、CONSTANT_Module、CONSTANT_Package
  -> dynamic constant 看 CONSTANT_Dynamic 和 BootstrapMethods
  -> record 看 java.lang.Record、组件字段、访问器、Record 属性
  -> sealed 看 PermittedSubclasses 属性
```

这些结构本质上仍然没有脱离主文档中的 `ClassFile` 框架。区别在于：语言特性越高级，越依赖访问标志、常量池新增项和属性表来保存额外语义。
