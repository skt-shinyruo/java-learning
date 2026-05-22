# 类加载器、双亲委派与线程上下文类加载器

理解类加载器时，先抓住一个核心规则：

```text
JVM 判断两个类型是否相同，不只看类名，还要看定义这个类的 ClassLoader。

类型身份 = 类的全限定名 + 定义类加载器
```

所以两个类即使包名、类名、字节码完全一样，只要由不同的类加载器定义，在 JVM 看来也是两个不同类型。

---

## 1. 双亲委派解决什么

双亲委派模型的基本过程是：

```text
一个类加载器收到加载请求
  -> 先交给父加载器
  -> 父加载器再继续向上委派
  -> 只有父加载器找不到时，子加载器才自己加载
```

它的主要价值是保证基础类型的一致性。比如 `java.lang.String`、`java.util.List` 这类基础 API 应该由更上层的加载器加载，避免应用程序自己定义一份同名核心类来破坏类型安全。

在 `java.lang.ClassLoader` 的常规实现里，双亲委派逻辑主要在 `loadClass()` 中：

```text
loadClass()
  -> 检查是否已经加载
  -> 委派父加载器加载
  -> 父加载器失败后，调用自己的 findClass()
```

因此自定义类加载器时，通常应该重写 `findClass()`，把“自己怎么找 class 字节码”的逻辑放进去，而不是直接改写 `loadClass()`。直接改写 `loadClass()` 很容易破坏双亲委派流程。

---

## 2. 插件系统为什么要用公共接口通信

跨 ClassLoader 通信时，不应该把具体实现类作为沟通类型，而应该把稳定的接口或父类放到父加载器中。

例如主程序和插件约定一个公共接口：

```java
package api;

public interface Plugin {
    void run();
}
```

插件实现类由插件包提供：

```java
package impl;

import api.Plugin;

public class HelloPlugin implements Plugin {
    @Override
    public void run() {
        System.out.println("Hello from plugin");
    }
}
```

主程序动态加载插件：

```java
URL pluginUrl = new File("hello-plugin.jar").toURI().toURL();

ClassLoader pluginLoader = new URLClassLoader(
    new URL[] { pluginUrl },
    ClassLoader.getSystemClassLoader()
);

Class<?> clazz = pluginLoader.loadClass("impl.HelloPlugin");
Object obj = clazz.getDeclaredConstructor().newInstance();

Plugin plugin = (Plugin) obj;
plugin.run();
```

这里可以强转，是因为主程序看到的 `Plugin` 和 `HelloPlugin` 实现的 `Plugin` 是同一个类型：

```text
api.Plugin + AppClassLoader
```

加载关系可以简化成：

```text
AppClassLoader
  -> 加载 api.Plugin
  -> 加载主程序

PluginClassLoader
  -> 加载 impl.HelloPlugin
  -> 遇到 api.Plugin 时先委派给 AppClassLoader
```

也就是说，`HelloPlugin` 的类型身份是：

```text
impl.HelloPlugin + PluginClassLoader
```

而它实现的接口身份是：

```text
api.Plugin + AppClassLoader
```

主程序里的 `Plugin` 也是：

```text
api.Plugin + AppClassLoader
```

所以 JVM 认为它们是同一个接口，强转成立。

---

## 3. 什么时候会出现 ClassCastException

如果插件加载器自己也定义了一份 `api.Plugin`，情况就不同了：

```text
api.Plugin + AppClassLoader
api.Plugin + PluginClassLoader
```

这两个 `api.Plugin` 的类名完全一样，但类型身份不同。此时主程序执行：

```java
Plugin plugin = (Plugin) obj;
```

仍然可能抛出：

```text
ClassCastException
```

所以插件系统通常会这样组织：

```text
app/
  api.jar              # 放 Plugin 接口，主程序加载
  main.jar             # 主程序

plugins/
  hello-plugin.jar     # 放 HelloPlugin 实现类
```

插件 jar 可以依赖 `api.jar` 编译，但不要再把 `api.Plugin` 打进插件包里。即使存在更复杂的自定义加载器，也要保证 `api.*` 这类公共契约优先委派给父加载器。

一句话版：

```text
公共接口、DTO、基础类型放父加载器；
具体实现类和实现私有依赖放子加载器；
跨 ClassLoader 调用时面向父加载器中的公共接口。
```

---

## 4. SPI 为什么需要线程上下文类加载器

插件例子里，是主程序主动创建 `PluginClassLoader`，再用它加载实现类。这个过程仍然符合双亲委派：子加载器先问父加载器，父加载器找不到实现类时，子加载器才自己加载。

SPI 场景的问题方向相反：

```text
父加载器加载的基础代码
  -> 需要发现和调用应用程序里的实现类
```

以 JDK 8 语境下的 JDBC 为例，可以粗略理解成：

```text
BootstrapClassLoader
  -> 加载 java.sql.Driver
  -> 加载 java.sql.DriverManager

AppClassLoader
  -> 加载 com.mysql.cj.jdbc.Driver
```

`DriverManager` 属于 JDK 基础代码，它需要发现应用依赖里的 JDBC 驱动实现，比如 MySQL 驱动：

```text
com.mysql.cj.jdbc.Driver
```

但启动类加载器的搜索范围不包含应用程序 ClassPath 或应用依赖里的第三方 jar。按照普通双亲委派方向，只有子加载器会向父加载器请求加载；父加载器不会自然地反过来知道子加载器里有哪些类。

这时就需要线程上下文类加载器：

```java
ClassLoader loader = Thread.currentThread().getContextClassLoader();
```

普通 Java 应用里，如果没有特别设置，线程上下文类加载器通常是应用类加载器：

```text
Thread.contextClassLoader = AppClassLoader
```

于是 JDK 基础代码可以不使用自己的定义加载器，而是借当前线程挂着的上下文类加载器去发现和加载应用层实现。

这就是所谓：

```text
父加载器加载的基础代码
  -> 通过 Thread Context ClassLoader 拿到应用类加载器
  -> 加载应用 ClassPath 下的 SPI 实现类
```

---

## 5. ServiceLoader 的简化流程

JDK 6 以后，`java.util.ServiceLoader` 给 SPI 加载提供了统一入口。更贴近 JDBC 自动加载过程的伪代码可以这样理解：

```java
// java.sql.DriverManager，JDK 基础类，由上层加载器加载
public class DriverManager {
    static {
        loadInitialDrivers();
    }

    private static void loadInitialDrivers() {
        ServiceLoader<Driver> loadedDrivers =
            ServiceLoader.load(Driver.class);

        for (Driver driver : loadedDrivers) {
            // 触发驱动类加载和注册
        }
    }
}
```

这里的关键是，`DriverManager` 自己属于 JDK 基础代码，但它要发现的是应用依赖里的 JDBC 驱动实现。`ServiceLoader.load(Driver.class)` 的典型调用是：

```java
ServiceLoader<Driver> drivers = ServiceLoader.load(Driver.class);
```

它的默认加载方式可以简化理解为：

```java
public static <S> ServiceLoader<S> load(Class<S> service) {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    return ServiceLoader.load(service, loader);
}
```

然后 `ServiceLoader` 会用这个 `loader` 查找服务配置：

```text
META-INF/services/java.sql.Driver
```

配置文件内容类似：

```text
com.mysql.cj.jdbc.Driver
```

之后再由应用类加载器加载这个实现类：

```text
AppClassLoader.loadClass("com.mysql.cj.jdbc.Driver")
```

所以关键点不是父加载器真的直接调用了某个子加载器变量，而是父层基础代码通过当前线程暴露出来的上下文类加载器，获得了一个能看见应用类路径的加载器。

---

## 6. 插件模型和 SPI 的关系

两者共同点是：

```text
接口/API 放上层加载器；
具体实现放下层加载器；
调用时面向上层加载器定义的接口。
```

区别在于触发加载的方向不同：

| 场景 | 谁触发加载 | 加载方向 | 关键机制 |
| --- | --- | --- | --- |
| 插件系统 | 主程序主动创建插件加载器 | 子加载器遵守双亲委派 | 公共接口由父加载器加载 |
| SPI / JDBC / JNDI | JDK 或框架基础代码发现实现 | 父层代码借助应用加载器 | 线程上下文类加载器 |

插件系统里的主程序通常明确知道要加载哪个插件类：

```java
pluginLoader.loadClass("impl.HelloPlugin");
```

SPI 里的 JDK 或框架基础代码通常不知道具体实现类名，它先通过约定配置发现实现：

```text
META-INF/services/接口全限定名
```

再通过线程上下文类加载器加载实现类。

---

## 7. JDK 9 模块化后的类加载器

JDK 9 引入 Java Platform Module System（JPMS）以后，JDK 自身不再主要以 `rt.jar`、`tools.jar` 这类大 jar 的形式组织，而是被拆成一组命名模块：

```text
java.base
java.sql
java.xml
java.desktop
jdk.compiler
...
```

这并不表示“每个模块都有一个独立类加载器”。更准确的说法是：

```text
每个模块会被定义到某一个类加载器上；
一个类加载器可以负责很多个模块。
```

也就是说，关系更接近：

```text
Module -> ClassLoader
```

而不是：

```text
Module -> 独立 ClassLoader
```

JDK 9 仍然保留三层内置类加载器结构，只是各层职责发生了变化：

```text
Bootstrap ClassLoader
  -> 负责核心 Java SE / JDK 模块
  -> 在 ClassLoader API 中仍然用 null 表示

Platform ClassLoader
  -> 取代 JDK 8 及以前的 Extension ClassLoader
  -> 负责一部分平台级 Java SE / JDK 模块
  -> 可通过 ClassLoader.getPlatformClassLoader() 获取

Application ClassLoader
  -> 负责应用类路径上的类
  -> 负责应用模块路径上的普通应用模块
  -> 不再保证是 java.net.URLClassLoader 的实例
```

以 JDK 9 的官方模块划分为例，`java.sql`、`java.scripting`、`java.xml.crypto` 等模块定义给平台类加载器；`java.base`、`java.xml`、`java.desktop`、`java.naming` 等模块定义给启动类加载器；应用模块路径上的普通模块默认定义给应用类加载器。具体模块清单会随 JDK 版本变化，理解时不要死记某个版本的完整列表，重点是“模块先归属到某个加载器”。

模块化以后，平台类加载器和应用类加载器的加载流程不再只是机械地先问父加载器，而是先看系统中已经解析出来的命名模块：

```text
Application ClassLoader 收到类加载请求
  -> 先检查这个类是否属于某个内置加载器负责的命名模块
  -> 如果属于，就交给该模块的负责加载器
  -> 如果不属于，再委派给父加载器 Platform ClassLoader
  -> 父加载器也找不到时，再搜索应用 ClassPath
  -> ClassPath 上找到的类属于应用加载器的 unnamed module
```

`Platform ClassLoader` 也类似：

```text
Platform ClassLoader 收到类加载请求
  -> 先检查这个类是否属于某个内置加载器负责的命名模块
  -> 如果属于，就交给该模块的负责加载器
  -> 如果不属于，再委派给父加载器 Bootstrap ClassLoader
```

这里最容易误解的点是：平台类加载器在特殊场景下也可能把请求交给应用类加载器。例如升级模块路径上的某个模块依赖应用模块路径上的模块时，平台类加载器可能需要让应用类加载器加载目标模块里的类。这已经不是传统“只能子问父”的树状委派。

因此 JDK 9 以后的加载顺序可以粗略记成：

```text
先看模块归属；
模块归属明确时，交给负责该模块的加载器；
模块归属不明确时，再回到父类委派和 ClassPath 搜索。
```

举两个例子：

```text
java.lang.String
  -> 属于 java.base
  -> java.base 由 Bootstrap ClassLoader 负责

java.sql.DriverManager
  -> 属于 java.sql
  -> JDK 9 中 java.sql 由 Platform ClassLoader 负责
```

这也是为什么有些 JDK 8 时代的代码到了 JDK 9+ 会出问题：

```java
URLClassLoader loader =
    (URLClassLoader) ClassLoader.getSystemClassLoader();
```

JDK 9+ 中应用类加载器和平台类加载器都不再保证是 `URLClassLoader`，这类强转可能直接抛 `ClassCastException`。

还要注意类路径和模块路径的区别：

```text
ModulePath
  -> 用来定位一个个模块
  -> 模块有 module-info.class 或被识别为 automatic module

ClassPath
  -> 仍然按传统方式定位 class 和 resource
  -> ClassPath 上的类进入 Application ClassLoader 的 unnamed module
```

如果同一个包既存在于命名模块中，又存在于 ClassPath 上，模块系统会优先维护命名模块的边界，ClassPath 上的同包内容可能不会按旧时代“补包”的方式参与加载。这样做是为了避免同一个包跨模块、跨加载器被拆开，破坏模块封装和类型一致性。

最后补充一点：JPMS 的 `ModuleLayer` API 允许框架构造新的模块层，并选择“多个模块共用一个加载器”或“每个模块使用独立加载器”等策略。但这是自定义模块层的能力，不代表 JDK 启动层默认就是一个模块一个类加载器。

官方口径可参考 OpenJDK [JEP 261: Module System](https://openjdk.org/jeps/261) 的 `Class loaders` 小节。

---

## 8. 实践规则

设计插件或 SPI 机制时，可以按下面几条规则检查：

1. 公共接口、抽象父类、DTO、异常类型放在父加载器可见的位置。
2. 实现类放在子加载器或应用加载器负责的范围。
3. 插件包不要重复打包公共 API，避免同名类型被不同加载器各自定义。
4. 自定义类加载器优先重写 `findClass()`，不要轻易改写 `loadClass()`。
5. 当“基础代码”需要回调应用实现时，通过 `Thread.currentThread().getContextClassLoader()` 或 `ServiceLoader` 获取应用侧实现。
6. JDK 9+ 不要假设系统类加载器或平台类加载器是 `URLClassLoader`。
7. 自定义类加载器在 JDK 9+ 中需要平台类时，优先委派给 `ClassLoader.getPlatformClassLoader()`，不要只按旧经验直接依赖 Bootstrap。

最后可以把三句话分开记：

```text
双亲委派：子加载器先问父加载器，保证基础类型一致。

线程上下文类加载器：让父层基础代码有机会回头找到应用层实现。

JDK 9 模块化：先看模块归属，再交给负责该模块的加载器。
```
