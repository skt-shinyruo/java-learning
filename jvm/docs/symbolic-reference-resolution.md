# 从符号引用到直接引用

Class 文件里的常量池保存的是平台无关的名字信息。JVM 真正执行字节码前后，会把这些名字解析成可以定位运行时结构的引用。这个过程通常被概括为：

```text
符号引用 -> 直接引用
```

更准确地说：

> 符号引用是“用名字描述目标”；直接引用是“JVM 已经能直接定位目标运行时结构”。

这里的“直接引用”不是 JVM 规范规定的一种固定指针格式。不同 JVM 实现可以用类元数据指针、字段偏移量、方法元数据指针、方法表索引、运行时句柄、调用缓存项等形式表达。

---

## 1. 示例代码

先看一个最常见的例子：

```java
public class ResolutionDemo {
    public static void main(String[] args) {
        System.out.println("hello");
    }
}
```

用 `javap -v -c ResolutionDemo` 查看，`main` 方法的核心字节码通常接近：

```text
0: getstatic     #2   // Field java/lang/System.out:Ljava/io/PrintStream;
3: ldc           #3   // String hello
5: invokevirtual #4   // Method java/io/PrintStream.println:(Ljava/lang/String;)V
8: return
```

字节码指令本身没有直接保存 `System`、`out`、`PrintStream`、`println` 这些完整信息，而是保存常量池索引：

```text
getstatic     #2
ldc           #3
invokevirtual #4
```

`#2`、`#3`、`#4` 指向 Class 文件常量池中的条目。常量池条目再通过多级索引保存类名、字段名、方法名和描述符。

在 JVM 规范里，解析并不只服务于 `getstatic` 和 `invokevirtual`。运行时常量池里需要解析的典型符号引用包括：

```text
CONSTANT_Class                 -> 类或接口
CONSTANT_Fieldref              -> 字段
CONSTANT_Methodref             -> 类方法、静态方法、特殊方法、构造器等
CONSTANT_InterfaceMethodref    -> 接口方法
CONSTANT_MethodType            -> 方法类型
CONSTANT_MethodHandle          -> 方法句柄
CONSTANT_InvokeDynamic         -> 动态调用点
```

前四类直接对应“类、字段、方法、接口方法”的查找规则；后三类是 Java 7 以后为 `java.lang.invoke` 和 `invokedynamic` 引入的链接结构，会进一步依赖字段、方法、接口方法解析。

---

## 2. 常量池里的符号引用

`System.out.println("hello")` 相关的常量池条目可以简化成：

```text
#2 = Fieldref
     class_index          -> java/lang/System
     name_and_type_index  -> out:Ljava/io/PrintStream;

#3 = String
     string_index         -> hello

#4 = Methodref
     class_index          -> java/io/PrintStream
     name_and_type_index  -> println:(Ljava/lang/String;)V
```

其中 `Fieldref`、`Methodref` 不是运行时字段地址或方法地址，而是符号描述：

```text
字段符号引用 = 所属类 + 字段名 + 字段描述符
方法符号引用 = 所属类 + 方法名 + 方法描述符
```

例如：

```text
java/lang/System.out:Ljava/io/PrintStream;
java/io/PrintStream.println:(Ljava/lang/String;)V
```

描述符也只是文本规则：

```text
Ljava/io/PrintStream;       -> java.io.PrintStream 类型
(Ljava/lang/String;)V       -> 参数是 String，返回 void
```

这些信息在 Class 文件里可以跨平台保存。不同操作系统、不同 CPU、不同 JVM 实现加载同一个 `.class` 文件时，都先看到同一组符号引用。

如果把 JVM 规范中的几类 resolution 放在一起，可以先有一个总览：

| 解析类型 | 常量池项 | 符号信息 | 解析后的语义目标 |
| --- | --- | --- | --- |
| Class resolution | `CONSTANT_Class` | 内部类名或数组类型描述 | 运行时类或接口 |
| Field resolution | `CONSTANT_Fieldref` | class + field name + descriptor | 某个字段 |
| Method resolution | `CONSTANT_Methodref` | class + method name + descriptor | 某个类方法、静态方法、构造器或特殊方法 |
| InterfaceMethod resolution | `CONSTANT_InterfaceMethodref` | interface + method name + descriptor | 某个接口方法 |
| MethodType resolution | `CONSTANT_MethodType` | 方法描述符 | `java.lang.invoke.MethodType` 实例 |
| MethodHandle resolution | `CONSTANT_MethodHandle` | reference kind + 字段/方法引用 | `java.lang.invoke.MethodHandle` 实例 |
| InvokeDynamic resolution | `CONSTANT_InvokeDynamic` | bootstrap method + name + descriptor | 动态调用点的 `CallSite` |

这个表的重点是：常量池里仍然是符号级描述，解析之后才变成 JVM 可以直接使用的运行时对象、字段、方法、句柄或调用点。

---

## 3. 解析发生在什么时候

类加载大致包含这些阶段：

```text
加载 -> 验证 -> 准备 -> 解析 -> 初始化
```

解析阶段的任务之一，就是把运行时常量池中的符号引用解析成直接引用。

规范层面可以把解析理解成一组不同入口：

```text
Class resolution
  -> 解析 CONSTANT_Class

Field resolution
  -> 先解析字段所属 class
  -> 再按字段规则查找 name + descriptor

Method resolution
  -> 先解析方法所属 class
  -> 再按类方法规则查找 name + descriptor

InterfaceMethod resolution
  -> 先解析接口
  -> 再按接口方法规则查找 name + descriptor

MethodType / MethodHandle / InvokeDynamic resolution
  -> 在前面几类解析基础上，构造 MethodType、MethodHandle 或 CallSite 等运行时结构
```

但“解析阶段”不一定表示所有符号引用都必须在类初始化前一次性解析完。JVM 允许实现选择不同策略：

```text
主动解析：类加载链接阶段提前解析一部分符号引用
懒解析：第一次真正执行相关字节码时再解析
```

无论采用哪种策略，第一次成功解析后，后续对同一个符号引用的解析应该得到同一个运行时实体。实现通常会把解析结果缓存起来，避免每次都按名字重新查找。

还有一个容易混淆的点：解析某个类符号引用可能触发目标类的加载，但加载、链接、解析不等于初始化。执行 `<clinit>` 属于初始化阶段，通常由 `new`、`getstatic`、`putstatic`、`invokestatic` 等主动使用场景触发。

---

## 4. 类符号引用解析

以 `java/io/PrintStream` 为例，常量池里保存的是内部名：

```text
java/io/PrintStream
```

解析类符号引用时，JVM 会使用当前类的定义类加载器去定位这个类型。成功后，JVM 得到目标类或接口的运行时表示。

抽象成流程就是：

```text
当前类的运行时常量池
  -> 找到 CONSTANT_Class: java/io/PrintStream
  -> 使用当前类的定义类加载器加载或查找 java.io.PrintStream
  -> 得到 PrintStream 的运行时类元数据
```

在 HotSpot 里，这类运行时类元数据常会被描述成 `Klass*` 一类的结构。但这是实现细节，不是 JVM 规范要求的内存格式。

HotSpot 的类解析结果可以粗略理解成：

```text
CONSTANT_Class "java/io/PrintStream"
  -> InstanceKlass / Klass 这类元数据结构
     - 类型名
     - 父类
     - 接口表
     - 字段表
     - 方法表
     - vtable / itable 等分派辅助结构
```

后面的字段解析、方法解析和接口方法解析，都会在这个类元数据基础上继续查找。

类解析还要做访问检查。例如当前类是否有权限访问目标类：

```text
public class              -> 跨包可访问
package-private class     -> 只能同包访问
```

如果找不到类，可能抛出：

```text
NoClassDefFoundError
ClassNotFoundException  // 通过反射或类加载 API 时更常见
```

如果目标类不可访问，可能抛出：

```text
IllegalAccessError
```

---

## 5. 字段符号引用解析：`getstatic System.out`

`getstatic #2` 对应的符号引用是：

```text
Fieldref java/lang/System.out:Ljava/io/PrintStream;
```

它由三部分组成：

```text
class       = java/lang/System
name        = out
descriptor  = Ljava/io/PrintStream;
```

字段解析可以分成两步：

```text
先解析 class 部分
再在目标类层次结构中找字段
```

具体到这个例子：

```text
1. 解析 java/lang/System
   -> 得到 System 的运行时类元数据

2. 在 System 相关结构中查找字段
   -> 字段名必须是 out
   -> 字段描述符必须是 Ljava/io/PrintStream;

3. 做访问检查
   -> 当前类是否能访问 System
   -> 当前类是否能访问 System.out

4. 得到可直接定位字段的信息
```

JVM 规范里的字段查找不是只看目标类自己。简化理解为：

```text
先查目标类 C 自己声明的字段
再查 C 的直接超接口
再查 C 的父类
然后沿父类链继续查找
```

解析成功后，直接引用可能包含：

```text
System 的类元数据引用
out 字段的元数据信息
静态字段存储位置或偏移量
字段类型信息
```

如果换成实例字段，例如：

```java
class Person {
    int age;
}

int value = person.age;
```

`getfield` 对应的字段解析结果在 HotSpot 里常会包含实例字段 offset。执行时就可以近似理解为：

```text
person reference/oop
  -> 对象地址
  -> 对象地址 + age 字段 offset
  -> 读取 int 值
```

静态字段没有“对象地址 + 实例字段 offset”这条路径。它属于类本身，解析结果会指向类元数据关联的静态字段存储位置。也就是说，同样是 Field resolution，实例字段更容易落到对象内 offset，静态字段更容易落到类相关存储位置。

对 `getstatic` 来说，字段解析成功还不等于只“拿到地址”。执行 `getstatic` 时还要按 JVM 规则确保声明该字段的类已经初始化，然后才能读取静态字段值。

如果字段不存在，可能抛出：

```text
NoSuchFieldError
```

如果字段存在但不可访问，可能抛出：

```text
IllegalAccessError
```

---

## 6. 方法符号引用解析：`invokevirtual PrintStream.println`

`invokevirtual #4` 对应的符号引用是：

```text
Methodref java/io/PrintStream.println:(Ljava/lang/String;)V
```

它由三部分组成：

```text
class       = java/io/PrintStream
name        = println
descriptor  = (Ljava/lang/String;)V
```

方法解析的第一步仍然是解析 class 部分：

```text
java/io/PrintStream
  -> PrintStream 的运行时类元数据
```

对 `CONSTANT_Methodref` 来说，class 部分必须解析成类类型，而不是接口类型。如果这里解析出来的是接口，规范会把它视为不兼容的类变化错误。

然后 JVM 按方法解析规则查找匹配方法：

```text
方法名相同：println
方法描述符相同：(Ljava/lang/String;)V
```

普通类方法引用 `CONSTANT_Methodref` 的核心查找路径可以简化成：

```text
目标类 C
  -> C 自己
  -> C 的父类
  -> 父类的父类
  -> ...
  -> 找不到时，还要考虑超接口中的匹配方法规则
```

Java 8 之后接口可以有 default method，因此方法解析不能只理解成“查类和父类链”。规范还定义了“最大特定超接口方法”（maximally-specific superinterface methods）相关规则，用于处理从接口继承来的非抽象方法。

解析成功后，JVM 会做访问检查：

```text
当前类是否能访问 PrintStream
当前类是否能访问 println(String)
```

解析结果可能包含：

```text
PrintStream 的类元数据引用
println(String) 的方法元数据引用
虚方法表索引或其他分派辅助信息
方法入口相关信息
```

在 HotSpot 里，方法本身通常会落到 `Method*` 这类元数据结构。它不是 Java 层面的 `java.lang.reflect.Method` 对象，而是 VM 内部描述方法的结构，通常会关联：

```text
方法名和描述符
访问标志
字节码和异常表
解释器入口
JIT 编译后的入口
所属类
vtable index 等分派信息
```

如果目标方法是静态方法，`invokestatic` 在解析并检查成功后，执行时通常可以直接使用解析到的 `Method*` 或等价入口；如果是 `invokevirtual`，解析得到的 `Method*` 只是分派过程的起点，还要结合 receiver 的实际类型继续选择最终实现。

如果方法不存在，可能抛出：

```text
NoSuchMethodError
```

如果方法存在但不可访问，可能抛出：

```text
IllegalAccessError
```

---

## 7. 解析目标不等于最终分派目标

`invokevirtual` 有一个关键点：解析 `Methodref` 得到的是“被引用的方法”，不一定是这次调用最终进入的方法。

例如：

```java
class Parent {
    void hello() {
        System.out.println("parent");
    }
}

class Child extends Parent {
    @Override
    void hello() {
        System.out.println("child");
    }
}

public class DispatchDemo {
    public static void main(String[] args) {
        Parent p = new Child();
        p.hello();
    }
}
```

`p.hello()` 的字节码可能类似：

```text
invokevirtual #7  // Method Parent.hello:()V
```

常量池符号引用写的是：

```text
Parent.hello:()V
```

解析时，JVM 能确认 `Parent.hello:()V` 这个方法存在、可访问，并得到被引用方法的运行时结构。

但真正执行 `invokevirtual` 时，操作数栈上的接收者对象实际类型是 `Child`。所以 JVM 还要做虚方法分派：

```text
操作数栈 receiver = new Child()
  -> 查看 receiver 的实际类 Child
  -> 在 Child 的虚方法表或等价结构中找到 hello() 的实际目标
  -> 进入 Child.hello()
```

HotSpot 的常见实现可以理解成：

```text
receiver oop
  -> 对象头里的 klass pointer
  -> Child 的 Klass / InstanceKlass
  -> 按 vtable index 找到实际 Method*
  -> 跳到解释器入口或编译入口
```

`vtable` 解决的是类继承体系里的虚方法分派：父类和子类在相同虚方法槽位上放各自的实现。这样 `Parent.hello` 解析阶段得到的槽位信息，可以在执行时配合 `Child` 的实际类元数据找到 `Child.hello`。

因此要分清两件事：

```text
解析 resolution：
  常量池符号引用 -> 被引用的类、字段、方法等运行时结构

分派 dispatch：
  根据调用指令和 receiver 实际类型 -> 本次调用最终进入哪个方法实现
```

`invokestatic` 和大多数 `invokespecial` 调用不需要普通虚方法分派；`invokevirtual` 和 `invokeinterface` 则需要根据运行时对象类型选择实现。

---

## 8. 接口方法引用解析

接口调用通常使用 `CONSTANT_InterfaceMethodref` 和 `invokeinterface`。

例如：

```java
interface Printer {
    void print(String value);
}

class ConsolePrinter implements Printer {
    @Override
    public void print(String value) {
        System.out.println(value);
    }
}

public class InterfaceDispatchDemo {
    public static void main(String[] args) {
        Printer printer = new ConsolePrinter();
        printer.print("hello");
    }
}
```

字节码调用点可能类似：

```text
invokeinterface #9,  2  // InterfaceMethod Printer.print:(Ljava/lang/String;)V
```

接口方法解析先确认符号引用里的 class 部分确实是接口，然后在接口及其父接口中查找匹配的方法：

```text
interface = Printer
name      = print
desc      = (Ljava/lang/String;)V
```

接口方法解析的规范目标是“接口中的那个方法”。它不同于类方法解析：

```text
CONSTANT_Methodref
  -> class 部分必须解析成类类型
  -> 按类方法解析规则找方法

CONSTANT_InterfaceMethodref
  -> class 部分必须解析成接口类型
  -> 按接口方法解析规则找方法
```

如果常量池项类型和目标类型不匹配，例如把接口方法当作普通类方法解析，就可能出现 `IncompatibleClassChangeError` 这类链接错误。

接口方法解析的查找规则也不是简单地“只看当前接口”。按 Java 8 口径可以简化为：

```text
接口 C 自己声明的方法
  -> java.lang.Object 中匹配的 public 实例方法
  -> C 的父接口中的 maximally-specific 方法
```

这里把 `Object` 的公共实例方法纳入考虑，是为了让接口类型上的 `toString()`、`hashCode()`、`equals(Object)` 这类调用在规范层面有明确目标。

解析成功后，JVM 知道“调用点引用的是 `Printer.print(String)` 这个接口方法”。但执行 `invokeinterface` 时，仍然要根据 receiver 的实际类型 `ConsolePrinter` 找到具体实现：

```text
receiver 实际类 ConsolePrinter
  -> 查找它对 Printer.print(String) 的实现
  -> 进入 ConsolePrinter.print(String)
```

HotSpot 通常用 `itable` 或等价结构帮助接口分派。可以粗略理解为：

```text
receiver oop
  -> receiver 实际类的 Klass
  -> 找到这个类针对 Printer 接口的接口方法表
  -> 根据接口方法槽位找到 ConsolePrinter.print 的 Method*
```

`itable` 和 `vtable` 的目标相似，都是让运行时分派少做名字查找；区别在于 `vtable` 面向类继承的虚方法槽位，`itable` 面向“某个类如何实现某个接口”的映射。

也就是说，接口方法同样要区分：

```text
接口方法引用解析
接口调用运行时分派
```

---

## 9. MethodType 解析

`CONSTANT_MethodType` 保存的是一个方法描述符，例如：

```text
(Ljava/lang/String;)V
```

它不表示“某个具体类里的某个方法”，而是表示一种调用形状：

```text
参数类型列表 = java.lang.String
返回类型     = void
```

MethodType resolution 的核心过程可以理解成：

```text
CONSTANT_MethodType
  -> 读取方法描述符
  -> 解析描述符里出现的类或接口类型
  -> 构造或找到对应的 java.lang.invoke.MethodType 实例
```

例如 Java 8 lambda 或方法句柄相关字节码里，经常可以看到：

```text
CONSTANT_MethodType  ()V
CONSTANT_MethodType  (Ljava/lang/String;)V
CONSTANT_MethodType  (Ljava/lang/Object;)Ljava/lang/Object;
```

它们解析后的结果不是 `Method*`，而是 Java 层可见的 `MethodType` 对象。这个对象描述“参数和返回值长什么样”，后续可以被 `MethodHandle`、`CallSite`、`invokedynamic` 用来检查调用点类型是否匹配。

如果描述符中的类无法解析，MethodType resolution 也会失败。也就是说，`(Lpkg/Missing;)V` 这种描述符会牵出对 `pkg.Missing` 的类解析。

---

## 10. MethodHandle 解析

`CONSTANT_MethodHandle` 用来描述一个方法句柄常量。它不是只保存方法名，而是保存：

```text
reference_kind   -> 句柄种类
reference_index  -> 指向 Fieldref / Methodref / InterfaceMethodref
```

`reference_kind` 决定这个 handle 表示哪类操作。常见种类包括：

| reference kind | 含义 | 底层引用 |
| --- | --- | --- |
| `REF_getField` | 读实例字段 | `CONSTANT_Fieldref` |
| `REF_getStatic` | 读静态字段 | `CONSTANT_Fieldref` |
| `REF_putField` | 写实例字段 | `CONSTANT_Fieldref` |
| `REF_putStatic` | 写静态字段 | `CONSTANT_Fieldref` |
| `REF_invokeVirtual` | 虚方法调用 | `CONSTANT_Methodref` |
| `REF_invokeStatic` | 静态方法调用 | `CONSTANT_Methodref` 或接口方法引用 |
| `REF_invokeSpecial` | 特殊实例调用 | `CONSTANT_Methodref` 或接口方法引用 |
| `REF_newInvokeSpecial` | 构造器调用 | `CONSTANT_Methodref`，目标通常是 `<init>` |
| `REF_invokeInterface` | 接口方法调用 | `CONSTANT_InterfaceMethodref` |

MethodHandle resolution 会先解析 `reference_index` 指向的字段或方法引用，再按 `reference_kind` 检查这个引用是否适合构造对应句柄。

可以抽象成：

```text
CONSTANT_MethodHandle
  -> reference_kind = REF_invokeVirtual
  -> reference_index = Methodref java/io/PrintStream.println:(Ljava/lang/String;)V
  -> 执行 Method resolution
  -> 做访问检查和 kind 约束检查
  -> 得到 java.lang.invoke.MethodHandle 实例
```

如果是字段句柄：

```text
CONSTANT_MethodHandle
  -> reference_kind = REF_getField
  -> reference_index = Fieldref Person.age:I
  -> 执行 Field resolution
  -> 得到能读取 Person.age 的 MethodHandle
```

这里的 `MethodHandle` 是 Java 层对象，但它背后会关联 VM 能执行的目标信息。HotSpot 内部可能把它连接到字段 offset、`Method*`、适配器入口、LambdaForm、编译后的调用路径等结构。学习时可以先抓住一点：`CONSTANT_MethodHandle` 是“把字段访问或方法调用包装成可传递、可调用的运行时句柄”。

更完整的 `MethodHandle`、`MethodType`、`CallSite` 关系见 [MethodHandle 与 invokedynamic](method-handle-invokedynamic.md)。

---

## 11. InvokeDynamic 解析

`CONSTANT_InvokeDynamic` 描述的是一个动态调用点。它不直接写死目标类和目标方法，而是保存：

```text
bootstrap_method_attr_index  -> BootstrapMethods 属性中的引导方法
name_and_type_index          -> 调用点名称和调用点描述符
```

例如 Java 8 lambda：

```java
Runnable task = () -> System.out.println("run");
```

字节码中可能出现：

```text
invokedynamic #12  // InvokeDynamic #0:run:()Ljava/lang/Runnable;
```

它的符号信息大致是：

```text
调用点名称      = run
调用点描述符    = ()Ljava/lang/Runnable;
bootstrap method = LambdaMetafactory.metafactory(...)
bootstrap args   = MethodType / MethodHandle / MethodType ...
```

InvokeDynamic resolution 的核心不是“在某个类里按 name + descriptor 找方法”，而是链接调用点：

```text
CONSTANT_InvokeDynamic
  -> 找到 BootstrapMethods 中的 bootstrap method
  -> 解析 bootstrap method 对应的 MethodHandle
  -> 解析调用点 name + descriptor
  -> 解析 bootstrap arguments
  -> 调用 bootstrap method
  -> 得到 java.lang.invoke.CallSite
  -> 校验 CallSite.target 的 MethodType 与调用点描述符兼容
  -> 缓存这个调用点的链接结果
```

`invokedynamic` 第一次链接成功后，后续执行同一个调用点时不再重复调用 bootstrap method，而是直接使用缓存的 `CallSite` 目标。不同类型的 `CallSite` 决定后续目标是否可以变化：

```text
ConstantCallSite -> target 固定
MutableCallSite  -> target 可以变化，需要同步语义配合
VolatileCallSite -> target 可以变化，并带 volatile 可见性语义
```

所以 `InvokeDynamic resolution` 和普通 `Method resolution` 的差别很大：

```text
普通方法解析：
  常量池 Methodref -> 类元数据里的某个方法

invokedynamic 解析：
  常量池 InvokeDynamic -> 调用 bootstrap method -> 得到 CallSite -> 调用 CallSite.target
```

HotSpot 实现层会把调用点链接结果放入相应的运行时缓存结构，并让解释器和 JIT 能够把后续调用优化成直接或近似直接的调用路径。

---

## 12. 访问检查与错误

解析不是“字符串匹配成功就结束”。找到目标后，JVM 还要检查访问权限。

访问检查至少涉及：

```text
目标类或接口是否可访问
字段或方法是否可访问
public / protected / private / package-private 是否允许
当前类与目标类的包关系、继承关系是否满足规则
```

常见错误包括：

| 场景 | 可能错误 |
| --- | --- |
| 类符号引用解析失败 | `NoClassDefFoundError` |
| 字段不存在 | `NoSuchFieldError` |
| 方法不存在 | `NoSuchMethodError` |
| 目标不可访问 | `IllegalAccessError` |
| `MethodType` 描述符中的类无法解析 | `NoClassDefFoundError` |
| `MethodHandle` 的 reference kind 和底层引用不匹配 | `IncompatibleClassChangeError` 及其子类 |
| `invokedynamic` 引导方法失败 | `BootstrapMethodError` |
| 方法引用类型不匹配，例如期望类方法却遇到接口方法 | `IncompatibleClassChangeError` 及其子类 |

这些错误多出现在“编译时和运行时 classpath 不一致”的场景。例如编译时存在某个字段，运行时换成了另一个旧版本 jar，字段被删除了，那么执行到相关 `getstatic` 或 `getfield` 时就可能出现 `NoSuchFieldError`。

---

## 13. 解析结果如何缓存

如果每次执行：

```text
invokevirtual #4
```

都重新从字符串 `java/io/PrintStream.println:(Ljava/lang/String;)V` 开始查找，成本会很高。因此 JVM 实现通常会缓存解析结果。

可以抽象成：

```text
第一次执行或提前解析时：
  #4 -> Methodref java/io/PrintStream.println:(Ljava/lang/String;)V
     -> 解析 class
     -> 查找方法
     -> 访问检查
     -> 得到运行时方法结构
     -> 写入常量池相关缓存

后续执行：
  #4 -> 直接使用已解析结果或相关缓存项
```

HotSpot 里可能涉及运行时常量池、常量池缓存、解析引用数组、解释器调用缓存、JIT 编译后的内联缓存等结构。它们分别服务于不同执行层次：

```text
运行时常量池相关缓存：记录符号引用已经解析到哪个运行时实体
vtable / itable：帮助虚方法和接口方法分派
inline cache：优化某个调用点在实际运行中经常遇到的 receiver 类型
```

把 HotSpot 相关结构放进 `System.out.println("hello")` 的执行链路里，可以这样理解：

```text
getstatic #2
  -> ConstantPool / ConstantPoolCache 中的字段解析结果
  -> System 的 InstanceKlass
  -> out 静态字段的存储位置
  -> 读取 PrintStream oop

invokevirtual #4
  -> ConstantPool / ConstantPoolCache 中的方法解析结果
  -> 被引用方法 PrintStream.println 的 Method*
  -> receiver oop 的 klass pointer
  -> PrintStream 或子类的 InstanceKlass
  -> vtable index 找到实际 Method*
  -> 进入解释器入口或编译入口
```

如果是接口调用：

```text
invokeinterface #9
  -> ConstantPool / ConstantPoolCache 中的接口方法解析结果
  -> receiver oop 的 klass pointer
  -> receiver 实际类的 InstanceKlass
  -> itable 找到该接口方法的实现 Method*
  -> 进入目标方法入口
```

如果某个调用点运行时总是遇到同一个 receiver 类型，解释器或 JIT 还可能使用 inline cache 记录这个类型和目标方法：

```text
调用点 A 第一次看到 Child
  -> 记录 receiver klass = Child
  -> 记录 target Method* = Child.hello

调用点 A 下次还是 Child
  -> 快速命中 inline cache
  -> 直接跳到 Child.hello 的入口
```

这不是替代规范解析，而是在解析和分派结果之上的执行优化。若调用点后来遇到多个 receiver 类型，inline cache 可能变成多态缓存，或者交给更通用的分派路径处理。

这些机制可以配合工作，但概念上不要混成一件事。解析缓存回答的是：

```text
这个常量池符号引用已经解析到哪个运行时实体？
```

调用分派和调用点优化回答的是：

```text
这一次调用，根据 receiver 实际类型，应该进入哪个实现？
这个调用点能不能被更快地执行？
```

---

## 14. 规范概念 vs HotSpot 实现

JVM 规范定义的是语义结果，而不是具体内存布局。

规范层可以这样说：

```text
运行时常量池中的符号引用会被动态确定为具体值。
字段、方法、接口方法等引用各有解析规则。
解析成功后，后续解析应继续成功并得到同一个实体。
```

HotSpot 实现层可能这样落地：

```text
类       -> Klass* / InstanceKlass 等类元数据
字段     -> field 信息、静态字段存储、实例字段 offset
方法     -> Method*、vtable index、方法入口
接口调用 -> itable、接口方法表相关结构
调用点   -> ConstantPoolCache、inline cache、JIT 后机器码
```

更细一点，可以按“规范目标”和“HotSpot 常见落地”对照：

| 规范解析目标 | HotSpot 常见落地 |
| --- | --- |
| `CONSTANT_Class` 解析成运行时类或接口 | `Klass*`、`InstanceKlass*`、数组 `Klass*` |
| `CONSTANT_Fieldref` 解析成字段 | 字段元数据、实例字段 offset、静态字段存储位置 |
| `CONSTANT_Methodref` 解析成类方法 | `Method*`、解释器入口、编译入口、vtable index |
| `CONSTANT_InterfaceMethodref` 解析成接口方法 | 接口方法元数据、itable 相关槽位 |
| `CONSTANT_MethodType` 解析成方法类型 | `java.lang.invoke.MethodType` 对象及其 VM 表示 |
| `CONSTANT_MethodHandle` 解析成方法句柄 | `java.lang.invoke.MethodHandle`、目标 `Method*` 或字段访问信息 |
| `CONSTANT_InvokeDynamic` 解析成动态调用点 | `CallSite`、target `MethodHandle`、调用点缓存、JIT 优化入口 |
| 后续执行缓存 | `ConstantPoolCache`、resolved references、inline cache、编译后机器码内嵌假设 |

`ConstantPoolCache` 可以理解为 HotSpot 为解释器和链接过程准备的“已解析常量池项旁路表”。字节码里仍然写着常量池索引，但执行时可以通过缓存项更快拿到字段 offset、方法入口、vtable index、itable 信息等。

`vtable` 和 `itable` 是分派结构，不是符号引用本身：

```text
符号引用解析：
  Parent.hello:()V -> 找到被引用方法和分派所需元数据

vtable 分派：
  receiver 实际类 Child + vtable index -> Child.hello Method*

itable 分派：
  receiver 实际类 ConsolePrinter + Printer 接口方法槽位 -> ConsolePrinter.print Method*
```

`inline cache` 更偏运行时性能优化：

```text
它记录“这个调用点最近或经常遇到哪些 receiver 类型，以及对应目标方法是什么”。
```

JIT 甚至可能在机器码中内嵌类型检查和直接调用：

```text
if (receiver.klass == Child) {
    call Child.hello compiled entry
} else {
    fallback to normal dispatch
}
```

这类优化必须在类加载、类重定义、依赖失效等场景下保持语义正确。因此它们属于实现层执行优化，不改变 JVM 规范里的解析和分派规则。

写学习笔记时可以用 HotSpot 名词帮助理解，但要记住：

> `Klass*`、`Method*`、字段 offset、vtable、itable、inline cache 都是实现视角；JVM 规范只要求虚拟机能按规则解析并执行，不要求所有实现使用同样的数据结构。

---

## 15. 总结流程

以：

```text
invokevirtual #4  // Method java/io/PrintStream.println:(Ljava/lang/String;)V
```

为例，从符号引用到直接引用可以压缩成：

```text
字节码操作数 #4
  -> 当前类运行时常量池 #4
  -> CONSTANT_Methodref
       class = java/io/PrintStream
       name  = println
       desc  = (Ljava/lang/String;)V
  -> 解析 class
       java/io/PrintStream -> PrintStream 的运行时类元数据
  -> 按方法解析规则查找
       name + desc 都匹配的方法
  -> 做访问检查
       类可访问、方法可访问
  -> 得到可直接定位运行时方法结构的信息
       方法元数据引用 / 方法表索引 / 调用缓存项等
  -> 缓存在运行时常量池相关结构中
  -> invokevirtual 执行时再根据 receiver 实际类型做虚方法分派
```

放到 HotSpot 视角，可以继续细化成：

```text
CONSTANT_Methodref
  -> ConstantPoolCache 记录解析结果
  -> 被引用方法 Method*
  -> receiver oop
  -> receiver 对象头中的 klass pointer
  -> InstanceKlass 的 vtable
  -> vtable slot 中的实际 Method*
  -> 方法入口
```

字段引用类似：

```text
getstatic #2  // Field java/lang/System.out:Ljava/io/PrintStream;
  -> CONSTANT_Fieldref
       class = java/lang/System
       name  = out
       desc  = Ljava/io/PrintStream;
  -> 解析 System 类
  -> 按字段解析规则查找 out 字段
  -> 做访问检查
  -> 得到字段元数据、静态字段位置或偏移量等信息
  -> 缓存解析结果
  -> getstatic 执行时确保类初始化并读取字段值
```

实例字段则更接近：

```text
getfield #n  // Field Person.age:I
  -> CONSTANT_Fieldref
  -> 解析 Person 类和 age:I 字段
  -> HotSpot 缓存 age 字段 offset
  -> 执行时用 object address + field offset 读取字段
```

七类 resolution 可以压缩成一张图：

```text
Class
  CONSTANT_Class -> 运行时类/接口

Field
  CONSTANT_Fieldref -> class resolution -> 字段查找 -> 字段直接定位信息

Method
  CONSTANT_Methodref -> class resolution -> 类方法查找 -> Method 直接定位信息

InterfaceMethod
  CONSTANT_InterfaceMethodref -> interface resolution -> 接口方法查找 -> 接口方法信息

MethodType
  CONSTANT_MethodType -> 解析描述符中的类型 -> MethodType

MethodHandle
  CONSTANT_MethodHandle -> 解析底层字段/方法引用 -> MethodHandle

InvokeDynamic
  CONSTANT_InvokeDynamic -> 调用 bootstrap method -> CallSite -> target MethodHandle
```

一句话收束：

> 从符号引用到直接引用，本质上是 JVM 把常量池里的 `class/name/descriptor` 这种名字描述，按规范规则解析成可以直接定位类、字段、方法等运行时结构的信息，并把解析结果缓存起来供后续执行使用。

---

## 16. 参考规范

这篇文档按 Java 8 学习口径整理，关键规则可以对照 JVM 规范：

- [JVMS 5.1 Run-Time Constant Pool](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.1)
- [JVMS 5.4 Linking](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4)
- [JVMS 5.4.3 Resolution](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3)
- [JVMS 5.4.3.1 Class and Interface Resolution](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.1)
- [JVMS 5.4.3.2 Field Resolution](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.2)
- [JVMS 5.4.3.3 Method Resolution](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.3)
- [JVMS 5.4.3.4 Interface Method Resolution](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.4)
- [JVMS 5.4.3.5 Method Type and Method Handle Resolution](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.5)
- [JVMS 5.4.3.6 Call Site Specifier Resolution](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.6)
- [JVMS 5.4.4 Access Control](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.4)
- [JVMS 6.5 Instructions](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5)
