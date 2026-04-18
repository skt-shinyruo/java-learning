# JCStress：部分有序性与 volatile 放置位置（TestOrderingPartial）

本页配合 `concurrency/src/test/java/yier/bubu/concurrency/jcstress/TestOrderingPartial.java`，用 **JCStress** 在真实 JVM/JIT/CPU 上枚举并统计结果，专门说明一个很容易误判的问题：

- 为什么 `1, 0` 这种“看到了后写入的值，却没看到先写入的值”的结果会出现
- 为什么 `volatile` 放在 `y` 上可以禁止 `1, 0`
- 为什么把 `volatile` 改放到 `x` 上以后，很多人以为更强，实际上 `1, 0` 仍然可能出现

> 这组例子的关键不在于“有没有 volatile”，而在于：**volatile 是否放在正确的发布-读取链路上。**

---

## 1) 测试结构

三个 case 的并发结构都一样：

- 写线程：`x = 1; y = 1;`
- 读线程：先读 `y`，再读 `x`
- `II_Result` 中：
  - `r1` 记录读到的 `y`
  - `r2` 记录读到的 `x`

因此结果 `1, 0` 的含义非常明确：

- 读线程看到了 `y=1`
- 但同一次观测里没有看到 `x=1`

如果你直觉上觉得“既然 `x` 比 `y` 写得更早，读到 `y=1` 时就不该再看到 `x=0`”，那正好是这组用例想澄清的误区。

---

## 2) Case1：两个字段都是普通字段

关键点（见 `TestOrderingPartial.Case1`）：

- `x`、`y` 都是普通 `int`
- 没有 `volatile`
- 没有锁
- 没有其他 happens-before

所以这是一个典型 data race。

在这种写法下：

- `0, 0`：写线程还没影响到读线程，可出现
- `0, 1`：读到了 `x=1`，但没读到 `y=1`，可出现
- `1, 1`：两个新值都读到了，可出现
- `1, 0`：最反直觉，但同样允许

这里把 `1, 0` 标成 `ACCEPTABLE_INTERESTING`，因为它正好暴露出“没有同步时，线程并不会按你脑中的单线程顺序去观察彼此的写入”。

---

## 3) Case2：把 `y` 声明为 volatile

关键点（见 `TestOrderingPartial.Case2`）：

- 写线程：先普通写 `x=1`，再 volatile 写 `y=1`
- 读线程：先 volatile 读 `y`，再普通读 `x`

这是标准的“数据 + 发布标记”模型。

一旦读线程观察到 `y=1`，就意味着：

- 这次 volatile 读看到了写线程对应的 volatile 写
- 两者之间建立了 synchronizes-with / happens-before
- 写线程在这次 volatile 写之前的普通写 `x=1`，也必须对读线程可见

因此：

- `1, 0` 在这里应该是 `FORBIDDEN`

这也是 `volatile` 最常见、最正确的用法之一：

- 普通字段承载数据
- volatile 字段承载“发布已经完成”的标记

---

## 4) Case3：把 `x` 声明为 volatile

关键点（见 `TestOrderingPartial.Case3`）：

- 写线程：先 volatile 写 `x=1`，再普通写 `y=1`
- 读线程：先普通读 `y`，再 volatile 读 `x`

很多人第一次看到这个 case 会误以为：

- “既然 `x` 都变成 volatile 了，那肯定比 Case2 更强”
- “所以 `1, 0` 也应该被禁止”

这是错误的。

### 为什么错

volatile 只有在“同一个 volatile 变量的写-读配对”上建立 happens-before，而且只覆盖：

- volatile 写之前的普通操作
- volatile 读之后的普通操作

但 Case3 里：

- `y=1` 发生在 volatile 写 `x=1` 之后
- 所以 `y=1` 不是“被这次 volatile 写发布出去的数据”
- 读线程对 `y` 的读取发生在 volatile 读 `x` 之前
- 所以这次读 `y` 也不受这次 volatile 读的 acquire 保护

换句话说，Case3 的 volatile 放在了“错误的位置”：

- Case2 中，volatile 是“发布标记”
- Case3 中，volatile 是“数据字段本身”

这两种写法不是等价变体，而是不同的内存语义。

因此：

- `1, 0` 在 Case3 中仍然可能出现
- 更合理的标注是 `ACCEPTABLE_INTERESTING`
- 如果把它写成 `FORBIDDEN`，通常是把 volatile 的作用范围想得太宽了

---

## 5) 三个 Case 的核心对比

可以把这三种写法压缩成下面这张表：

| Case | 写线程 | 读线程 | `1, 0` |
| --- | --- | --- | --- |
| Case1 | `x=1; y=1;` | `read y; read x;` | 允许 |
| Case2 | `x=1; volatile y=1;` | `read volatile y; read x;` | 禁止 |
| Case3 | `volatile x=1; y=1;` | `read y; read volatile x;` | 仍然允许 |

真正的分界线不是“有没有 volatile”，而是：

- 你是否用同一个 volatile 变量把“写入数据”和“读取数据”正确地连接起来了

---

## 6) 和现有文档的关系

如果你想把这组结果放回更完整的 JMM 语境里，可以继续读：

- [`volatile-jmm.md`](./volatile-jmm.md)：解释 release/acquire、publish/consume 以及为什么 Case2 有效而 Case3 无效
- [`jcstress-visibility.md`](./jcstress-visibility.md)：看另一组 JCStress 示例，理解“可见性 / 连贯性 / 停止标记”
- [`jmm-notes.md`](./jmm-notes.md)：回到 JMM 总览，把 happens-before、volatile、synchronized、final、CAS 放到同一条主线上

---

## 7) 如何运行

先把 `base` 模块安装到本地仓库，再编译 `concurrency` 的测试类：

```bash
mvn -pl base -am -DskipTests install
mvn -pl concurrency -am -DskipTests test-compile
```

生成 `concurrency` 模块的测试类路径：

```bash
mvn -f concurrency/pom.xml -DincludeScope=test dependency:build-classpath \
  -Dmdep.outputFile=/tmp/jcstress-ordering-partial.classpath
```

运行 JCStress（只跑 `TestOrderingPartial`）：

```bash
cd concurrency
java -cp "target/test-classes:target/classes:$(cat /tmp/jcstress-ordering-partial.classpath)" \
  org.openjdk.jcstress.Main -t yier.bubu.concurrency.jcstress.TestOrderingPartial
```

说明：

- JCStress 不是稳定单测，而是并发结果统计工具
- 某些“有趣结果”出现频率可能很低，但低频不等于不合法
- 如果你只想学习这一个模式，用 `-t` 过滤单个测试类最合适
- 这里没有继续使用 `exec-maven-plugin:java`，因为 JCStress 会 fork 子 JVM；在这个多模块工程里，显式类路径的方式更稳定，也已经过实际验证
