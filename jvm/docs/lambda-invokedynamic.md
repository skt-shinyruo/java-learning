# Lambda、invokedynamic 与 LambdaMetafactory

这篇文档记录 Java Lambda 编译和运行时的真实结构。核心结论：

```text
Lambda 主体会被 javac 抽成一个普通方法；
Lambda 表达式位置会被编译成 invokedynamic；
第一次执行 invokedynamic 时，JVM 调用 LambdaMetafactory；
LambdaMetafactory 负责生成函数式接口实现类的实例创建逻辑；
CallSite 保存这个 invokedynamic 调用点的链接结果，后续复用。
```

以下面代码为例：

```java
public class LambdaDemo {
    public static void main(String[] args) {
        Runnable r = () -> System.out.println("hello");
        r.run();
    }
}
```

---

## 1. Lambda 主体会被抽成方法

源码里的 Lambda 主体是：

```java
() -> System.out.println("hello")
```

`javac` 会把这段真正要执行的代码抽成一个方法，效果类似：

```java
private static void lambda$main$0() {
    System.out.println("hello");
}
```

所以，真正打印 `hello` 的代码不在 `invokedynamic` 指令里，而是在这个编译器生成的方法里。

---

## 2. Lambda 表达式位置会变成 invokedynamic

源码：

```java
Runnable r = () -> System.out.println("hello");
```

编译后的字节码结构大致是：

```text
0: invokedynamic #0  // run:()Ljava/lang/Runnable;
5: astore_1
6: aload_1
7: invokeinterface java/lang/Runnable.run:()V
```

这条 `invokedynamic` 的作用不是执行 `System.out.println("hello")`，而是产生一个 `Runnable` 实例。

换成 Java 形态理解，效果是：

```java
Runnable r = new LambdaDemo$$Lambda$1();
```

这里的 `LambdaDemo$$Lambda$1` 不是 `javac` 提前写到磁盘里的普通 `.class` 文件，而是 JVM 根据 `invokedynamic` 和 `LambdaMetafactory` 在运行时创建出来的实现类。

---

## 3. BootstrapMethods 指向 LambdaMetafactory

`invokedynamic #0` 不是孤立的。Class 文件里会有 `BootstrapMethods` 属性，描述这个动态调用点第一次执行时该怎么链接。

结构大致是：

```text
BootstrapMethods:
  0: java/lang/invoke/LambdaMetafactory.metafactory
     Method arguments:
       ()V
       LambdaDemo.lambda$main$0:()V
       ()V
```

它告诉 JVM：

```text
这个 invokedynamic 调用点由 LambdaMetafactory.metafactory 负责链接；
目标函数式接口是 Runnable；
接口方法是 Runnable.run()；
Lambda 真正执行的方法是 LambdaDemo.lambda$main$0()。
```

---

## 4. LambdaMetafactory 生成函数式接口实现

第一次执行到这条 `invokedynamic` 时，JVM 会调用：

```java
java.lang.invoke.LambdaMetafactory.metafactory(...)
```

传进去的关键信息包括：

```text
目标接口：Runnable
接口方法：run()V
Lambda 主体方法：LambdaDemo.lambda$main$0()V
```

`LambdaMetafactory` 会创建一个实现 `Runnable` 的类的实例创建逻辑。这个实现类效果类似：

```java
final class LambdaDemo$$Lambda$1 implements Runnable {
    @Override
    public void run() {
        LambdaDemo.lambda$main$0();
    }
}
```

于是原始代码：

```java
Runnable r = () -> System.out.println("hello");
```

运行效果类似：

```java
Runnable r = new LambdaDemo$$Lambda$1();
```

然后：

```java
r.run();
```

实际调用链是：

```text
r.run()
  -> LambdaDemo$$Lambda$1.run()
  -> LambdaDemo.lambda$main$0()
  -> System.out.println("hello")
```

---

## 5. CallSite 保存链接结果

`LambdaMetafactory.metafactory(...)` 的返回值是 `CallSite`。

`CallSite` 保存的是这个 `invokedynamic` 调用点的链接结果。对这个例子来说，它保存的信息可以理解为：

```text
这个 invokedynamic 调用点以后如何得到 Runnable 实例。
```

更底层地说，`CallSite` 内部有一个 target `MethodHandle`。这个 target 指向后续执行这个动态调用点时要走的目标逻辑。

第一次执行：

```text
invokedynamic
  -> 调用 LambdaMetafactory.metafactory(...)
  -> 生成 Runnable 实现类的实例创建逻辑
  -> 返回 CallSite
  -> JVM 把 CallSite 绑定到这个 invokedynamic 调用点
```

后续执行同一个 Lambda 表达式位置：

```text
invokedynamic
  -> 直接使用已经绑定的 CallSite
  -> 得到 Runnable 实例
```

所以，`LambdaMetafactory` 不会在同一个调用点的每次执行时都重新完整运行。

---

## 6. 五句话直接版

原始说法可以改成更直接的版本：

```text
1. javac 把 Lambda 里的代码抽成 LambdaDemo.lambda$main$0()。

2. javac 在 Lambda 表达式原来的位置放一条 invokedynamic，
   这条指令负责产生 Runnable 实例。

3. 第一次执行 invokedynamic 时，JVM 调用 LambdaMetafactory.metafactory(...)。

4. LambdaMetafactory 生成一个实现 Runnable 的类，
   这个类的 run() 方法会调用 LambdaDemo.lambda$main$0()。

5. JVM 把这次链接结果记录到 CallSite。
   后面再执行同一个 Lambda 表达式位置，就复用这个 CallSite，
   不再重新走 LambdaMetafactory 的完整流程。
```

一句话总结：

```text
Lambda 主体变成 lambda$main$0()；
Lambda 表达式位置变成 invokedynamic；
第一次运行时 LambdaMetafactory 生成 LambdaDemo$$Lambda$1 implements Runnable 的实现逻辑；
CallSite 记住这个 invokedynamic 的链接结果；
以后复用。
```

---

## 7. 这和 Lambda 慢有什么关系

Lambda 的首次执行可能比普通静态方法调用更重，因为第一次执行 `invokedynamic` 时要完成链接：

```text
读取 BootstrapMethods
解析 MethodType 和 MethodHandle
调用 LambdaMetafactory
创建函数式接口实现类的实例创建逻辑
生成并绑定 CallSite
```

这个成本主要发生在首次执行或冷路径里。一个 Lambda 调用点链接完成后，后续调用会复用 `CallSite`，JIT 也可能把稳定调用链内联优化掉。

所以不能简单说 Lambda 天然慢。更准确的说法是：

```text
Lambda 的实现机制比普通直接调用多了首次链接成本；
热路径中经过 JIT 优化后，Lambda 调用本身可能接近普通调用；
真正让代码明显变慢的常见原因，是 Stream 管道、装箱拆箱、额外对象分配和不严谨的基准测试。
```
