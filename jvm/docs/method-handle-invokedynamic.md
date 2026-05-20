# MethodHandle 与 invokedynamic

这篇文档说明 `MethodHandle`、`MethodType`、`VarHandle`、`CallSite`、`MethodHandles.Lookup`、`BootstrapMethods` 以及 `CONSTANT_MethodHandle`、`CONSTANT_MethodType` 这些结构之间的关系。

它们共同解决的问题是：让 JVM 可以把“方法调用、字段访问、动态语言调用点、lambda、动态常量、变量原子访问”表达成可链接、可缓存、可替换、可被 JIT 优化的运行时结构。

先给一个总览：

```text
运行时 API
  ├── MethodHandle          -> 可调用目标
  ├── MethodType            -> 方法签名
  ├── VarHandle             -> 变量位置访问句柄
  ├── CallSite              -> invokedynamic 链接后的调用点
  └── MethodHandles.Lookup  -> 创建 handle 的权限上下文

Class 文件结构
  ├── CONSTANT_MethodHandle -> 符号级方法/字段/构造器句柄描述
  ├── CONSTANT_MethodType   -> 方法描述符常量
  ├── CONSTANT_InvokeDynamic-> 动态调用点描述
  ├── CONSTANT_Dynamic      -> 动态常量描述
  └── BootstrapMethods      -> 动态调用/动态常量的引导方法表

字节码执行
  ├── invokedynamic
  ├── invokevirtual MethodHandle.invokeExact / invoke
  └── invokevirtual VarHandle.get / compareAndSet / ...
```

需要先区分两件事：

- 源码里使用 `MethodHandle` API，不等于 Class 文件常量池里一定会出现 `CONSTANT_MethodHandle`
- 常量池里的 `CONSTANT_MethodHandle` 是静态符号描述，运行时的 `java.lang.invoke.MethodHandle` 是 JVM 解析后的可调用对象

---

## 1. 版本演进

这些结构不是同一个 Java 版本一次性加入的：

| 内容 | 首次版本 | Class 主版本 | 说明 |
| --- | --- | --- | --- |
| `MethodHandle` | Java 7 | 51 | JSR 292，引入 `java.lang.invoke` 动态调用机制 |
| `MethodType` | Java 7 | 51 | 描述方法调用形状 |
| `MethodHandles.Lookup` | Java 7 | 51 | 创建、解析 handle 的权限上下文 |
| `CallSite` | Java 7 | 51 | `invokedynamic` 链接后的调用点 |
| `ConstantCallSite` | Java 7 | 51 | target 固定的调用点 |
| `MutableCallSite` | Java 7 | 51 | target 可变的调用点 |
| `VolatileCallSite` | Java 7 | 51 | target 可变且有 volatile 可见性语义 |
| `invokedynamic` | Java 7 | 51 | JVM 动态调用指令 |
| `CONSTANT_MethodHandle` | Java 7 | 51 | 常量池 tag `15` |
| `CONSTANT_MethodType` | Java 7 | 51 | 常量池 tag `16` |
| `CONSTANT_InvokeDynamic` | Java 7 | 51 | 常量池 tag `18` |
| `BootstrapMethods` | Java 7 | 51 | 给 `invokedynamic` 使用的 Class 级属性 |
| lambda 使用这套机制 | Java 8 | 52 | Java 8 lambda 大量使用 `invokedynamic` 和 `LambdaMetafactory` |
| `VarHandle` | Java 9 | 53 | JEP 193，引入变量位置访问句柄 |
| `CONSTANT_Dynamic` | Java 11 | 55 | 常量池 tag `17`，动态常量 |
| `BootstrapMethods` 支持动态常量 | Java 11 | 55 | 同一个属性也用于 `CONSTANT_Dynamic` 链接 |

`BootstrapMethods` 是 Java 7 就有的，不是 Java 8 lambda 才出现。只是 Java 8 lambda 让它在普通 Java 程序里变得非常常见。Java 11 之后，`CONSTANT_Dynamic` 又复用了这个属性。

---

## 2. 没有这些机制之前

在 `MethodHandle`、`invokedynamic`、`VarHandle`、`CONSTANT_Dynamic` 出现之前，JVM 并不是不能做动态能力，而是主要依赖几类旧机制：

```text
普通调用
  -> 固定的 invoke 字节码指令

动态方法调用
  -> 反射

lambda / 代理 / 动态语言适配
  -> 运行时生成 class，或者编译器生成匿名内部类

底层字段和原子访问
  -> volatile、Atomic 类、sun.misc.Unsafe、反射 Field

复杂常量
  -> static final 字段加 <clinit>
```

这些旧机制能解决一部分问题，但在类型安全、链接表达能力、运行时优化和标准化方面都有明显局限。

### 2.1 固定调用指令

早期 JVM 的普通方法调用主要依赖四条指令：

```text
invokestatic      调用静态方法
invokespecial     调用构造器、private 方法、super 方法
invokevirtual     调用普通实例方法
invokeinterface   调用接口方法
```

字段访问则依赖：

```text
getfield
putfield
getstatic
putstatic
```

Class 文件里配套的常量池项是：

```text
CONSTANT_Methodref
CONSTANT_InterfaceMethodref
CONSTANT_Fieldref
CONSTANT_NameAndType
CONSTANT_Class
```

这种模型适合普通 Java 静态调用：

```java
user.getName();
Math.max(a, b);
new User();
```

编译器能把“目标类、方法名、描述符”写进常量池，JVM 按 Java 的链接规则解析即可。但它不擅长表达这种需求：

```text
这个调用点第一次执行时，由语言运行时决定目标；
目标决定后缓存下来；
以后目标还能按运行时规则替换；
JIT 仍然能观察并优化这个调用点。
```

固定 `invoke*` 指令的问题不是慢，而是表达能力固定。它们描述的是 Java 风格的静态链接调用，不描述“由 bootstrap method 自定义链接规则”的动态调用点。

### 2.2 反射

没有 `MethodHandle` 时，动态调用常用反射：

```java
Method method = User.class.getDeclaredMethod("getName");
Object result = method.invoke(user);
```

反射解决了“运行时按名字找方法并调用”的问题，但它不适合作为 JVM 级高性能动态调用基础：

```text
参数通常走 Object[]
返回值通常是 Object
静态类型信息弱
装箱、拆箱、数组包装成本明显
访问检查和调用适配路径较重
JIT 很难把 method.invoke(...) 优化成普通 invokevirtual
```

`MethodHandle` 引入后，动态调用可以带着明确的 `MethodType`，并且以 JVM 更容易识别的调用目标形式存在。它不是简单替代反射的 API，而是给 JVM 一个可以链接、适配、组合和优化的调用模型。

### 2.3 生成 class 和匿名内部类

没有 `invokedynamic` 时，很多运行时动态能力要靠生成 class 或编译器生成额外 class。

Java 8 之前没有 lambda，类似逻辑通常写成匿名内部类：

```java
Runnable r = new Runnable() {
    @Override
    public void run() {
        System.out.println("hi");
    }
};
```

编译后会多出一个匿名内部类 `.class`。框架或动态语言也经常用 ASM、CGLIB、Javassist 等工具生成适配器 class：

```text
运行时生成字节码
  -> defineClass 加载
  -> 通过生成出来的方法做转发、派发、桥接
```

这种方式能工作，但有几个问题：

```text
会增加类数量和类加载成本
实现策略在编译期或框架层较早固化
JVM 很难知道这个调用点背后的语言级链接规则
动态语言需要自己维护缓存表、派发逻辑和失效逻辑
```

`invokedynamic` 把“这个调用点如何链接”开放给语言运行时或 JDK 的 metafactory。调用点第一次执行时由 bootstrap method 返回 `CallSite`，JVM 缓存它，并让 JIT 有机会优化稳定 target。

### 2.4 volatile、Atomic 和 Unsafe

没有 `VarHandle` 时，变量访问和并发原子操作主要依赖：

```text
普通字段读写
volatile 字段
synchronized
java.util.concurrent.atomic.*
sun.misc.Unsafe
反射 Field
```

普通业务代码通常够用，但 JDK 底层和高性能并发结构需要更细粒度能力：

```text
CAS
getAndAdd
有序写
acquire / release
数组元素原子更新
按字段偏移访问
```

这些能力过去大量依赖 `Unsafe`。`Unsafe` 的问题是：

```text
API 太底层
容易破坏类型安全和内存安全
不是理想的标准 Java API
和 Java 9 模块化封装方向冲突
```

`VarHandle` 的目标就是把这些变量访问能力标准化。它仍然足够底层，可以表达 CAS、volatile、acquire/release 等内存语义，但入口变成受访问控制约束的标准 API。

### 2.5 <clinit> 和复杂常量

没有 `CONSTANT_Dynamic` 时，常量池能直接表达的常量有限，例如：

```text
int / long / float / double
String
Class
方法和字段符号引用
MethodType
MethodHandle
```

复杂对象通常只能放到静态初始化里：

```java
static final Pattern P = Pattern.compile("[a-z]+");
```

编译后进入：

```text
<clinit>
```

这当然能工作，但它表达的是“类初始化时执行一段字节码”。有些语言运行时或字节码生成场景希望表达的是：

```text
这是一个常量池常量；
第一次解析它时调用 bootstrap method；
得到值后缓存为常量；
以后直接复用。
```

Java 11 的 `CONSTANT_Dynamic` 补上了这个能力。它让常量也可以像 `invokedynamic` 调用点一样，通过 `BootstrapMethods` 延迟链接。

### 2.6 引入后的问题分工

把新旧机制放在一起看，分工会更清楚：

| 新机制 | 之前常用方式 | 解决的问题 |
| --- | --- | --- |
| `MethodHandle` / `MethodType` | 反射 `Method.invoke`、生成适配器 | 动态调用弱类型、成本高、难优化 |
| `invokedynamic` / `CallSite` / `BootstrapMethods` | 固定 `invoke*`、生成 class、手写缓存表 | 调用点运行时链接、缓存、可替换、可被 JIT 观察 |
| `CONSTANT_MethodHandle` / `CONSTANT_MethodType` | 只能用普通 method/field ref 描述目标 | Class 文件缺少动态链接所需的句柄和签名材料 |
| `MethodHandles.Lookup` | 反射访问检查、`setAccessible` 等 | 创建 handle 时保留 Java 访问控制和模块边界 |
| `VarHandle` | `Unsafe`、Atomic 类、反射 Field | 标准化字段/数组元素访问、CAS、内存语义 |
| `CONSTANT_Dynamic` | `<clinit>` 初始化复杂静态字段 | 常量池常量也能延迟链接、计算并缓存 |

一句话总结：

```text
以前：
  固定字节码调用适合 Java 静态调用；
  动态能力靠反射、Unsafe、生成 class 补洞。

后来：
  JVM 把动态调用、动态常量、变量原子访问变成标准机制；
  既能动态，又能类型检查、访问控制、缓存和 JIT 优化。
```

---

## 3. MethodHandle

`MethodHandle` 表示一个运行时可调用目标。它可以指向：

- 普通实例方法
- 静态方法
- 构造方法
- `private`、`super`、default 等特殊调用目标
- 字段读取
- 字段写入
- 数组元素访问
- 由其他 `MethodHandle` 组合或适配出来的新调用逻辑

示例：

```java
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

MethodHandles.Lookup lookup = MethodHandles.lookup();

MethodHandle mh = lookup.findVirtual(
    String.class,
    "length",
    MethodType.methodType(int.class)
);

int n = (int) mh.invokeExact("hello");
```

源码目标是：

```text
String.length() : int
```

但 `mh` 的调用形状是：

```text
(String)int
```

原因是：实例方法的接收者 `this` 会变成 `MethodHandle` 的第一个参数。

### 3.1 invokeExact 和 invoke

`MethodHandle` 的两个核心调用方法是：

```java
invokeExact
invoke
```

区别：

| 方法 | 特点 |
| --- | --- |
| `invokeExact` | 调用点签名必须和 `MethodHandle.type()` 精确匹配 |
| `invoke` | 允许 JVM 做部分类型适配，例如引用类型转换、装箱拆箱等 |

它们在字节码里看起来像普通 `invokevirtual`：

```text
invokevirtual java/lang/invoke/MethodHandle.invokeExact
```

但 JVM 不按普通 Java 方法的固定签名解析它们。`invokeExact` 和 `invoke` 是 signature-polymorphic 方法，调用点描述符由源码调用处决定。

这就是为什么下面两处调用都可以写成 `invokeExact`，但调用点签名不同：

```java
int len = (int) lengthHandle.invokeExact("abc");
String s = (String) toStringHandle.invokeExact(123);
```

JVM 会把每个调用点看成不同的方法描述符，而不是只看 `MethodHandle.invokeExact(Object...)Object` 这种普通 Java 视角。

### 3.2 MethodHandle 和反射的区别

`MethodHandle` 不是普通反射的另一种写法。

| 对比项 | 反射 `Method` | `MethodHandle` |
| --- | --- | --- |
| 主要包 | `java.lang.reflect` | `java.lang.invoke` |
| 设计目标 | 通用元数据和反射调用 | JVM 级动态调用和优化 |
| 访问检查 | 典型情况下调用时仍有反射路径成本 | 创建 handle 时完成关键访问检查 |
| 调用形状 | `Object[]` 参数风格明显 | 强类型 `MethodType` |
| JVM 优化 | 较难优化到普通调用形态 | 可被 JIT 展开、内联、消除适配 |

可以把 `MethodHandle` 理解为更接近 JVM 链接机制的调用目标，而反射更偏 Java 语言层的元数据 API。

---

## 4. MethodType

`MethodType` 描述方法签名，不描述调用哪个方法。

它包含：

```text
返回类型
参数类型列表
```

例如：

```java
MethodType.methodType(int.class, String.class)
```

表示：

```text
(String)int
```

在 JVM 方法描述符里写作：

```text
(Ljava/lang/String;)I
```

每个 `MethodHandle` 都有一个 `MethodType`：

```java
MethodType type = mh.type();
```

`MethodType` 的作用是约束调用点形状。`invokeExact` 要求源码调用处的静态签名和 `mh.type()` 精确一致。

需要注意：源码里调用 `MethodType.methodType(...)` 通常只是普通静态方法调用，不一定让常量池出现 `CONSTANT_MethodType`。`CONSTANT_MethodType` 更常见于 `invokedynamic` 的 bootstrap 参数。

---

## 5. CONSTANT_MethodHandle 和 CONSTANT_MethodType

Class 文件里的 `CONSTANT_MethodHandle` 和 `CONSTANT_MethodType` 是常量池结构。

`CONSTANT_MethodHandle`：

```text
CONSTANT_MethodHandle_info {
    u1 tag;                 // 15
    u1 reference_kind;
    u2 reference_index;
}
```

`CONSTANT_MethodType`：

```text
CONSTANT_MethodType_info {
    u1 tag;                 // 16
    u2 descriptor_index;
}
```

`CONSTANT_MethodHandle` 的 `reference_index` 会指向一个字段或方法引用，例如：

```text
CONSTANT_Fieldref
CONSTANT_Methodref
CONSTANT_InterfaceMethodref
```

`CONSTANT_MethodType` 的 `descriptor_index` 指向一个方法描述符字符串，例如：

```text
()I
(Ljava/lang/String;)I
(Ljava/lang/Object;)Z
```

### 5.1 MethodHandle 的 9 种 reference kind

`CONSTANT_MethodHandle` 不是单一语义。它通过 `reference_kind` 区分 9 种引用类型：

| reference kind | 含义 | 接近的字节码指令 |
| --- | --- | --- |
| `REF_getField` | 实例字段读 | `getfield` |
| `REF_getStatic` | 静态字段读 | `getstatic` |
| `REF_putField` | 实例字段写 | `putfield` |
| `REF_putStatic` | 静态字段写 | `putstatic` |
| `REF_invokeVirtual` | 普通虚方法调用 | `invokevirtual` |
| `REF_invokeStatic` | 静态方法调用 | `invokestatic` |
| `REF_invokeSpecial` | `private`、`super`、特殊实例方法调用 | `invokespecial` |
| `REF_newInvokeSpecial` | 构造器调用 | `new` + `invokespecial <init>` |
| `REF_invokeInterface` | 接口方法调用 | `invokeinterface` |

构造器比较特殊。普通字节码创建对象通常是：

```text
new
dup
invokespecial <init>
```

而构造器 `MethodHandle` 的调用形状更像：

```text
(args) -> new Object
```

也就是输入构造参数，返回新对象。

---

## 6. BootstrapMethods

`BootstrapMethods` 是 Class 级属性，用来保存动态链接的引导方法信息。

结构大致是：

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

它服务两类结构：

```text
Java 7:
  CONSTANT_InvokeDynamic

Java 11:
  CONSTANT_Dynamic
```

对于 `invokedynamic`，`CONSTANT_InvokeDynamic` 里的 `bootstrap_method_attr_index` 会指向 `BootstrapMethods` 中的一项：

```text
invokedynamic 指令
  └── CONSTANT_InvokeDynamic
        ├── bootstrap_method_attr_index -> BootstrapMethods[n]
        │     └── bootstrap_method_ref -> CONSTANT_MethodHandle
        └── name_and_type_index -> 动态调用点名称和描述符
```

对于 `CONSTANT_Dynamic`，关系类似，只是解析结果不是调用点，而是一个动态计算出来的常量值。

---

## 7. invokedynamic 和 CallSite

`invokedynamic` 是 Java 7 加入的字节码指令。它和 `invokevirtual`、`invokestatic` 最大的不同是：目标方法不是在编译期通过普通符号引用固定下来的，而是通过 bootstrap method 在运行时链接。

第一次执行某个 `invokedynamic` 调用点时，JVM 大致做这些事：

```text
1. 读取 CONSTANT_InvokeDynamic
2. 找到 BootstrapMethods 中的 bootstrap method
3. 构造 bootstrap 参数：
   - MethodHandles.Lookup lookup
   - String name
   - MethodType type
   - 静态 bootstrap 参数
4. 调用 bootstrap method
5. bootstrap method 返回 CallSite
6. JVM 把 CallSite 绑定到当前 invokedynamic 调用点
7. 后续执行直接调用 CallSite.target
```

关系可以简化为：

```text
invokedynamic
  -> bootstrap method
  -> CallSite
  -> MethodHandle target
```

`CallSite` 不是 handle，但它内部有一个目标 handle：

```java
MethodHandle target
```

常见实现：

| 类型 | 特点 | 适合场景 |
| --- | --- | --- |
| `ConstantCallSite` | target 固定 | lambda、稳定动态调用点 |
| `MutableCallSite` | target 可以改变 | 动态语言运行时重绑定 |
| `VolatileCallSite` | target 可以改变，读取具有 volatile 可见性 | 需要跨线程及时看到 target 更新 |

调用点第一次链接有成本，但链接结果会缓存。JIT 如果看到 `CallSite` 目标稳定，可以把 `MethodHandle` 链展开并内联到接近普通方法调用的机器码。

---

## 8. Lambda 如何使用这套机制

Java 8 lambda 是最常见的例子。

源码：

```java
IntSupplier task = () -> snapshot + history.size();
```

编译后通常会有两部分：

```text
1. 一个保存 lambda body 的辅助方法
2. 一个 invokedynamic 调用点
```

例如主示例 `ClassFileTour` 中可以看到：

```text
private int lambda$compute$0(int);
  descriptor: (I)I
  flags: ACC_PRIVATE, ACC_SYNTHETIC
```

调用处：

```text
invokedynamic #53,  0
```

Class 级属性：

```text
BootstrapMethods:
  0: REF_invokeStatic java/lang/invoke/LambdaMetafactory.metafactory
    Method arguments:
      ()I
      REF_invokeSpecial yier/bubu/jvm/ClassFileTour.lambda$compute$0:(I)I
      ()I
```

这段结构表达的是：

```text
这个 lambda 调用点
  由 LambdaMetafactory.metafactory 负责链接
  真实执行逻辑是 ClassFileTour.lambda$compute$0
  函数式接口方法形状是 ()I
```

第一次执行时，JVM 调用 `LambdaMetafactory`。它返回一个 `CallSite`，其 target 负责创建或返回函数式接口实例。之后这个调用点就可以被缓存和优化。

这种设计让编译器不用提前固定 lambda 实现类形态，JVM 可以根据运行时环境选择更合适的实现策略。

---

## 9. MethodHandles.Lookup

`MethodHandles.Lookup` 是创建 `MethodHandle` 和 `VarHandle` 的权限上下文。

```java
MethodHandles.Lookup lookup = MethodHandles.lookup();
```

它携带：

```text
lookup class
访问模式：public / private / protected / package / module
```

常见方法：

```java
lookup.findVirtual(...)
lookup.findStatic(...)
lookup.findSpecial(...)
lookup.findConstructor(...)
lookup.findGetter(...)
lookup.findSetter(...)
lookup.findVarHandle(...)
lookup.findStaticVarHandle(...)
```

访问检查主要发生在创建 handle 的时候。成功拿到合法 `MethodHandle` 之后，后续调用不需要每次重复完整反射访问检查。

Java 9 模块系统之后，`Lookup` 还要考虑模块可读性、包导出和包开放。访问另一个类的私有成员时常见：

```java
MethodHandles.privateLookupIn(Target.class, lookup)
```

bootstrap method 也会收到 JVM 传入的 `Lookup`：

```java
bootstrap(
    MethodHandles.Lookup lookup,
    String name,
    MethodType type,
    ...
)
```

这个 `lookup` 代表调用点所在类的访问上下文，引导方法可以基于它解析目标。

---

## 10. VarHandle

`VarHandle` 是 Java 9 引入的变量位置访问句柄。它不是方法句柄，目标不是“可调用方法”，而是“可访问变量位置”。

它可以指向：

- 实例字段
- 静态字段
- 数组元素
- `byte[]` 或 `ByteBuffer` 的视图元素

示例：

```java
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

class User {
    int age;
}

VarHandle AGE = MethodHandles.lookup()
    .findVarHandle(User.class, "age", int.class);
```

使用：

```java
int oldValue = (int) AGE.get(user);
AGE.set(user, 18);
boolean updated = AGE.compareAndSet(user, 18, 19);
int previous = (int) AGE.getAndAdd(user, 1);
```

`VarHandle` 的重点是访问模式和内存语义：

| 访问模式 | 含义 |
| --- | --- |
| plain | 普通读写 |
| opaque | 较弱的跨线程可见性约束 |
| acquire | 读 acquire |
| release | 写 release |
| volatile | volatile 读写语义 |
| atomic | CAS、getAndAdd、getAndSet 等原子操作 |

常见方法：

```text
get
set
getVolatile
setVolatile
getAcquire
setRelease
compareAndSet
weakCompareAndSet
getAndAdd
getAndSet
```

JVM 可以把这些访问模式映射到底层字段访问、数组访问、CAS 指令和内存屏障。

Class 文件里没有 `CONSTANT_VarHandle`。`VarHandle` 是运行时对象，通常通过 `MethodHandles.Lookup` 创建。它的访问方法也具有签名多态特征，调用点签名由实际访问的变量坐标和变量类型决定。

---

## 11. CONSTANT_Dynamic

`CONSTANT_Dynamic` 是 Java 11 引入的动态常量，常量池 tag 是 `17`。

结构：

```text
CONSTANT_Dynamic_info {
    u1 tag;                              // 17
    u2 bootstrap_method_attr_index;
    u2 name_and_type_index;
}
```

它和 `CONSTANT_InvokeDynamic` 都会引用 `BootstrapMethods`，区别在于解析结果：

| 常量池项 | 服务对象 | bootstrap 返回 |
| --- | --- | --- |
| `CONSTANT_InvokeDynamic` | `invokedynamic` 指令 | `CallSite` |
| `CONSTANT_Dynamic` | 动态常量位置 | 一个常量值 |

可以这样理解：

```text
CONSTANT_InvokeDynamic
  -> bootstrap method
  -> CallSite
  -> 后续调用 CallSite.target

CONSTANT_Dynamic
  -> bootstrap method
  -> value
  -> 后续复用这个常量值
```

普通 Java 源码不一定容易让 `javac` 直接生成 `CONSTANT_Dynamic`。它更多出现在字节码生成工具、语言实现或 JDK 内部生成逻辑中。阅读到 `Dynamic` 常量池项时，要同时看：

- `NameAndType`：常量名和类型
- `bootstrap_method_attr_index`：对应的 bootstrap method
- bootstrap arguments：计算常量值所需的静态参数

---

## 12. JVM 如何优化

这套机制看起来动态，但目标不是“更慢的间接调用”。JVM 会把可稳定化的调用点优化掉。

大致过程：

```text
第一次执行
  -> 解析常量池
  -> 调用 bootstrap method
  -> 创建 MethodHandle / CallSite
  -> 缓存链接结果

后续执行
  -> 直接走缓存的 CallSite.target

JIT 编译后
  -> 识别稳定 CallSite
  -> 展开 MethodHandle 适配链
  -> 内联真实目标方法
  -> 消除装箱、桥接、类型适配等中间成本
```

`MethodHandle` 体系的设计目标是给 JVM 一个可链接、可组合、可优化的动态调用模型，而不是简单替代反射。

---

## 13. 总结

可以把这些概念压缩成下面几行：

```text
MethodHandle
  运行时可调用目标

CONSTANT_MethodHandle
  Class 文件里的符号级 handle 描述

MethodType
  方法签名

CONSTANT_MethodType
  Class 文件里的方法描述符常量

VarHandle
  变量位置的运行时访问句柄

CallSite
  invokedynamic 链接后的调用点，内部 target 是 MethodHandle

Lookup
  创建 MethodHandle / VarHandle 的权限上下文

BootstrapMethods
  Class 文件属性，告诉 JVM invokedynamic / dynamic constant 如何链接
```

主线关系是：

```text
Class 文件用 CONSTANT_MethodHandle、CONSTANT_MethodType、BootstrapMethods 描述动态链接材料；
JVM 用 Lookup 做访问控制和目标解析；
MethodHandle 表示可调用目标；
CallSite 承载 invokedynamic 的链接结果；
VarHandle 把类似能力扩展到字段、数组元素和原子访问；
JIT 再把稳定的动态结构优化成接近普通调用和字段访问的机器码。
```
