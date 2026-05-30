# Java 字符串拼接：从 StringBuilder 到 invokedynamic

这篇文档解释 JDK 8 和 JDK 9+ 编译字符串拼接时的差异，重点回答一个问题：

> JDK 9 以后，为什么说 Java 利用 `invokedynamic` 将字符串拼接的优化与 `javac` 生成的字节码解耦？

先给结论：

- JDK 8 中，`javac` 通常会把带变量的字符串拼接直接翻译成 `StringBuilder` 操作
- JDK 9+ 中，`javac` 通常会生成 `invokedynamic`，由运行时通过 `StringConcatFactory` 链接真正的拼接逻辑
- 业务代码不是直接调用 `StringConcatFactory`；它是 `invokedynamic` 调用点的 bootstrap method

---

## 1. 示例代码

可以先用一段很小的代码观察差异：

```java
public class StringConcat {
    public static String concat(String str) {
        return str + "aa" + "bb";
    }

    public static void main(String[] args) {
        String strByBuilder = new StringBuilder()
                .append("aa")
                .append("bb")
                .append("cc")
                .append("dd")
                .toString();

        String strByConcat = "aa" + "bb" + "cc" + "dd";

        System.out.println(strByBuilder);
        System.out.println(strByConcat);
    }
}
```

编译和反编译命令：

```bash
${JAVA_HOME}/bin/javac StringConcat.java
${JAVA_HOME}/bin/javap -v StringConcat.class
```

需要注意：全是字面量的拼接：

```java
String strByConcat = "aa" + "bb" + "cc" + "dd";
```

通常会在编译期直接折叠成：

```java
String strByConcat = "aabbccdd";
```

真正适合观察 JDK 8 和 JDK 9+ 差异的是带变量的拼接：

```java
return str + "aa" + "bb";
```

---

## 2. JDK 8：编译器固定成 StringBuilder

在 JDK 8 中，`javac` 通常会把：

```java
return str + "aa" + "bb";
```

编译成类似下面的字节码结构：

```text
0: new           #2  // class java/lang/StringBuilder
3: dup
4: invokespecial #3  // Method java/lang/StringBuilder."<init>":()V
7: aload_0
8: invokevirtual #4  // Method java/lang/StringBuilder.append:(Ljava/lang/String;)Ljava/lang/StringBuilder;
11: ldc           #5  // String aa
13: invokevirtual #4  // Method java/lang/StringBuilder.append:(Ljava/lang/String;)Ljava/lang/StringBuilder;
16: ldc           #6  // String bb
18: invokevirtual #4  // Method java/lang/StringBuilder.append:(Ljava/lang/String;)Ljava/lang/StringBuilder;
21: invokevirtual #7  // Method java/lang/StringBuilder.toString:()Ljava/lang/String;
```

也就是说，拼接策略已经被 `javac` 写进了 `.class` 文件：

```java
new StringBuilder()
        .append(str)
        .append("aa")
        .append("bb")
        .toString();
```

这种方式很好理解，但它的限制也很明显：如果未来 JDK 想换一种更好的字符串拼接实现，旧的编译结果已经写死了 `StringBuilder` 调用。运行时当然还能做 JIT 优化，但字节码层面的表达已经固定了。

---

## 3. JDK 9+：用 invokedynamic 描述拼接

JDK 9 以后，`javac` 不再总是把带变量的字符串拼接展开成 `StringBuilder`。它通常会生成类似这样的指令：

```text
invokedynamic #2,  0  // InvokeDynamic #0:makeConcatWithConstants:(Ljava/lang/String;)Ljava/lang/String;
```

同时，Class 文件的 `BootstrapMethods` 属性里会出现类似信息：

```text
java/lang/invoke/StringConcatFactory.makeConcatWithConstants
```

这表示当前字节码没有直接写死：

```text
一定要 new StringBuilder
一定要逐个 append
一定要最后 toString
```

而是在字节码里表达成：

```text
这里有一个字符串拼接调用点；
参数形状是一个 String 入参，返回 String；
拼接模板里还包含常量 "aa" 和 "bb"；
具体如何执行，由运行时链接。
```

---

## 4. StringConcatFactory 到底做什么

可以把 JDK 9+ 的流程理解成：

```text
Java 源码
  -> javac 生成 invokedynamic
  -> invokedynamic 指向 BootstrapMethods
  -> BootstrapMethods 调用 StringConcatFactory
  -> StringConcatFactory 返回 CallSite
  -> CallSite.target 是真正执行拼接的 MethodHandle
```

关键点是：业务代码不是直接调用 `StringConcatFactory`。

它更像是 `invokedynamic` 调用点的链接工厂。第一次执行到这个调用点时，JVM 会根据 Class 文件里的 `BootstrapMethods` 信息调用它，由它创建并返回一个 `CallSite`。这个 `CallSite` 内部有一个 target `MethodHandle`，后续真正的字符串拼接就通过这个 target 执行。

同一个 `invokedynamic` 调用点链接成功后，后续执行通常不会每次都重新调用 `StringConcatFactory`。JVM 会缓存这个调用点的链接结果，让它像普通调用一样继续执行，并给 JIT 留出优化空间。

---

## 5. 什么叫和 javac 生成的字节码解耦

JDK 8 的模式可以概括为：

```text
javac 决定怎么拼
  -> 字节码固定为 StringBuilder
  -> 运行时执行这套固定结构
```

JDK 9+ 的模式可以概括为：

```text
javac 只描述这里要拼字符串
  -> 字节码使用 invokedynamic
  -> 运行时用 StringConcatFactory 链接具体实现
```

所以“解耦”的意思是：

```text
字符串拼接的语义由 javac 表达；
字符串拼接的具体优化策略由运行时决定。
```

这样做的好处是，未来如果 JVM 或 JDK 想改进字符串拼接策略，不一定需要让 `javac` 生成一套全新的固定字节码。只要 `invokedynamic` 调用点的语义稳定，运行时就可以在 `StringConcatFactory`、`MethodHandle`、JIT 优化等层面继续演进。

换句话说，JDK 8 更像是：

```text
编译器把实现方案写死在字节码里。
```

JDK 9+ 更像是：

```text
编译器留下一个动态调用点，让运行时选择实现方案。
```

---

## 6. 一句话总结

JDK 9+ 确实专门提供了 `StringConcatFactory` 来服务字符串拼接，但准确说法不是“Java 代码调用 `StringConcatFactory` 做 concat”，而是：

```text
javac 为字符串拼接生成 invokedynamic；
invokedynamic 通过 BootstrapMethods 找到 StringConcatFactory；
StringConcatFactory 在运行时为这个拼接点生成 CallSite 和 MethodHandle；
后续拼接执行走已经链接好的调用目标。
```

这就是字符串拼接从“编译器固定生成 `StringBuilder`”转向“运行时链接和优化”的核心变化。
