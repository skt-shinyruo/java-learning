# JCStress：可见性/连贯性与停止标记（TestVisibility）

本页配合 `concurrency/src/test/java/yier/bubu/concurrency/jcstress/TestVisibility.java`，用 **JCStress** 在真实 JVM/JIT/CPU 上枚举并统计并发读写的结果分布，帮助理解 **Java 内存模型（JMM）** 下：

- 普通字段（存在 data race）时，读写结果为什么会“看起来不连贯”
- `volatile` 如何约束可见性与重排序，并避免“读到新值又回到旧值”
- `VarHandle` 不同访问模式（这里使用 `opaque`）的效果边界
- “停止标记”这种经典问题在无同步 vs. 有同步下的差异

> 备注：JCStress 的目标是“把概率现象变成统计结果”，它不是传统的稳定单测（unit test）。

---

## 1) JCStress 注解速览

- `@JCStressTest`：声明一个并发测试用例。
- `@State`：共享状态对象（被多个 actor 并发访问）。
- `@Actor`：一个并发执行的方法（可理解为一个线程的行为）。
- `III_Result`：结果容器（3 个 `int` 槽位），把读到的值写到 `r1/r2/r3`。
- `@Outcome`：声明结果是否可接受：
  - `ACCEPTABLE`：合理可出现
  - `ACCEPTABLE_INTERESTING`：规范允许但非常“反直觉/有教学意义”
  - `FORBIDDEN`：按该内存语义不应出现（出现说明假设不成立或实现/平台有问题）
- `Mode.Termination` + `@Signal`：终止性测试，一个线程自旋，另一个线程发送“停止信号”。

---

## 2) Case1：普通字段，多次读取是否连贯

关键点（见 `TestVisibility.Case1`）：

- `Foo.x` 是普通 `int`
- `p` 和 `q` 引用指向同一个对象（`q = p`）
- 读线程连续读三次：`p.x`、`q.x`、`p.x`
- 写线程并发写一次：`p.x = 3`

这个 case **故意制造 data race**，所以读线程可能出现“看起来不连贯”的观测，例如：

- `0, 3, 0`：中间读到了新值，但后续读又回到了旧值（`ACCEPTABLE_INTERESTING`）

直觉上很多人会认为“同一个变量不会倒退”，但在 data race 下，JIT/CPU 允许很多优化与缓存行为（例如消除重复读取、寄存器缓存、别名分析不确定导致对 `p.x`/`q.x` 的处理不一致），从而让你看到这种结果。

---

## 3) Case2：把 `x` 变成 `volatile`

关键点（见 `TestVisibility.Case2`）：

- 唯一变化：`volatile int x`

在这个 case 里：

- `0, 3, 0` 被标为 `FORBIDDEN`：它代表“读到新值后又倒退到旧值”
- `0, 0, 3` / `0, 3, 3` 这种“写发生在两次读之间”的结果是合理的

---

## 4) Case3：使用 VarHandle（Opaque）

关键点（见 `TestVisibility.Case3`）：

- `x` 仍然是普通 `int`
- 但通过 `VarHandle` 的 `getOpaque/setOpaque` 读写

`opaque` 可以粗略理解为“比 plain 更强、比 volatile 更弱”的访问模式。

这个 case 把 `0, 3, 0` 标为 `FORBIDDEN`，用来验证：**在该访问模式下对同一变量的多次读取是否仍然不应出现倒退**。

如果你在某些平台/JVM 上真的观测到了倒退结果，建议把访问模式改为：

- `getVolatile/setVolatile`（更强）
- 或 `getAcquire/setRelease`（常用于停止标记/发布-订阅）

---

## 5) Case4：停止标记（非 volatile）

关键点（见 `TestVisibility.Case4`，`Mode.Termination`）：

- 线程 A：`while (!stop) { }`
- 线程 B：`stop = true`
- `stop` 是普通 `boolean`

缺少同步时，线程 A 可能永远读不到更新，原因包括（但不限于）：

- JIT 可能把读取 hoist 到循环外（把循环优化成“永不退出”）
- CPU 缓存/寄存器导致一直观察到旧值

因此 `STALE` 是 `ACCEPTABLE_INTERESTING`。

---

## 6) Case5：停止标记（VarHandle）

关键点（见 `TestVisibility.Case5`）：

- 使用 `VarHandle` 的 `getOpaque/setOpaque` 读写停止标记
- 期望读线程能观测到停止信号并退出（非 `TERMINATED` 的结果被标为 `FORBIDDEN`）

提示：如果你的目标是“强保证”的停止标记（而不是实验 opaque 的边界），更推荐：

- `volatile boolean stop`
- 或 `getAcquire/setRelease` / `getVolatile/setVolatile`

---

## 7) 如何运行

前置：

- 建议使用 **JDK 9+**（本用例包含 `VarHandle`）

编译 `concurrency` 模块（只编译，不跑 JUnit）：

```bash
mvn -pl concurrency -am -DskipTests test-compile
```

运行 JCStress（只跑 `TestVisibility`）：

```bash
mvn -pl concurrency -am -DskipTests org.codehaus.mojo:exec-maven-plugin:3.3.0:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=org.openjdk.jcstress.Main \
  -Dexec.args="-t yier.bubu.concurrency.jcstress.TestVisibility"
```

说明：

- JCStress 运行时间可能较长，并且会尽量探索不同的交错时序；这是它的价值所在。
- 如果你只是在学习，建议先从只跑一个测试类开始（用 `-t` 过滤）。

