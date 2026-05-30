# Java `ThreadLocal`：为什么 key 是弱引用，以及这会不会影响使用

`ThreadLocal` 很容易被讲成一句“线程本地变量”，但真正容易踩坑的地方不在 API 表面，而在它背后的存储模型：

- `ThreadLocal` 自己并不直接保存“每个线程的值”
- 真正保存值的是每个线程自己的 `ThreadLocalMap`
- `ThreadLocalMap` 的 entry 里，key 是 `WeakReference<ThreadLocal<?>>`
- value 仍然是普通强引用

这份文档只聚焦一个常见疑问：

- 为什么 `ThreadLocal` 的 key 要做成弱引用
- 弱引用被 GC 清掉后，会不会影响 `ThreadLocal` 的正常使用
- 为什么在线程池里仍然必须手动 `remove()`

如果你想先补齐并发主线，再回来看 `ThreadLocal` 的实现取舍，可以先读 [jmm-notes.md](./jmm-notes.md)；如果你在看虚拟线程，想理解它和 `ThreadLocal` 的关系，可以再结合 [virtual-threads.md](./virtual-threads.md)。

---

## 1. 先把存储模型看对

很多人第一次接触 `ThreadLocal` 时，会下意识以为数据是存在 `ThreadLocal` 对象里的。实际上不是。

更接近真实实现的心智模型是：

```text
Thread
  -> threadLocals: ThreadLocalMap
       -> Entry[]
            -> key   = weak(ThreadLocal<?>)
            -> value = Object
```

也就是说：

- `ThreadLocal` 更像“访问当前线程局部槽位的句柄”
- 每个线程自己维护一份 `ThreadLocalMap`
- 同一个 `ThreadLocal`，在不同线程里会映射到不同的 value

所以当你写：

```java
private static final ThreadLocal<UserContext> CTX = new ThreadLocal<>();
```

然后在某个线程里执行：

```java
CTX.set(userContext);
```

真正发生的是：

- 当前线程取到自己的 `ThreadLocalMap`
- 以 `CTX` 作为 key，在这个线程自己的 map 里保存 `userContext`

这也是为什么 `ThreadLocal` 适合表达“与当前执行线程绑定的上下文”。

---

## 2. 为什么 key 要用弱引用

先看如果 key 不是弱引用，会有什么问题。

`ThreadLocalMap` 是挂在线程对象上的，而线程池里的工作线程往往会存活很久。如果 entry 的 key 对 `ThreadLocal` 是强引用，那么就可能出现这种链路：

```text
Thread
  -> ThreadLocalMap
       -> Entry
            -> key(ThreadLocal)  // 强引用
            -> value
```

这意味着：

- 只要线程还活着，`ThreadLocalMap` 就还活着
- 只要 map 还活着，entry 就还活着
- 只要 entry 还活着，里面的 `ThreadLocal` key 和 value 就都还活着

结果就是：即使业务代码早就不再持有这个 `ThreadLocal` 了，它也可能因为线程还活着而一直无法回收。

把 key 设计成弱引用，目的就是切断这条“无意义的保活链”：

- 当外部代码已经不再持有某个 `ThreadLocal` 实例时
- GC 可以把这个 `ThreadLocal` key 回收掉
- 这样至少不会因为线程对象长期存在，就把一个已经没人使用的 `ThreadLocal` 永远拴在线程上

所以，弱引用 key 的核心目的不是“优化访问”，而是“降低长期线程场景下的泄漏风险”。

---

## 3. 弱引用 key 被 GC 清掉，会不会影响正常使用

正常使用不会。

关键在于：**只要你的代码里还持有 `ThreadLocal` 的强引用，GC 就不会把它回收。**

典型正常写法是：

```java
private static final ThreadLocal<UserContext> CTX =
        ThreadLocal.withInitial(UserContext::new);
```

这里 `CTX` 是一个稳定的强引用。既然外部还有强引用在，`ThreadLocalMap` 里的弱引用 key 就不会被 GC 清掉，所以：

- `CTX.get()` 还能找到当前线程对应的值
- `CTX.set(...)` 还能更新当前线程对应的值
- 正常生命周期内完全不受影响

这也是为什么很多工程里的 `ThreadLocal` key 基本不会被回收：它们通常被定义成类字段，甚至是 `static final` 字段。只要这个类还没被卸载，静态字段就会一直强引用着那个 `ThreadLocal` 实例，弱引用 key 自然不会变成 `null`。

真正会让 key 被 GC 清掉的，往往是这种写法：

```java
new ThreadLocal<String>().set("x");
```

这一行执行完后，如果没有其他地方再持有这个 `ThreadLocal` 对象，那么下一次 GC 时：

- 这个匿名 `ThreadLocal` 可能被回收
- `ThreadLocalMap` 里的 key 可能变成 `null`
- 但 value 可能还留在线程的 map 里

这时你已经没有那个 `ThreadLocal` 实例了，所以也谈不上“继续正常使用它”。`ThreadLocal` 对象本身就是访问当前线程 value 的 key；如果这个 key 已经被回收，说明业务代码也已经没有同一个 `ThreadLocal` 句柄可以拿来执行 `get()`。从语义上说，这种 key 被清掉并不是功能错误，而是 JVM 在告诉你：这个 `ThreadLocal` 句柄本身已经没人持有了。

因此，要把两件事分开：

- “正常使用会不会失效”不会，只要你还持有 `ThreadLocal`
- “key 会不会在错误或松散使用下被回收”会，而且这是设计使然

---

## 4. 真正要警惕的不是 key 消失，而是 stale entry

`ThreadLocal` 最容易被误解的一点是：

- key 是弱引用
- 但 value 不是弱引用

这会产生一种特殊状态：**key 已经被 GC 清掉了，但 value 还被线程的 `ThreadLocalMap` 强引用着。**

可以把这种 entry 理解成：

```text
Entry
  key   = null
  value = someObject
```

这类 entry 通常被称为 stale entry。它的问题在于：

- 业务代码已经拿不到原来的 `ThreadLocal` 了
- 但 value 还没立刻释放
- 只要线程还长期存活，这块 value 就可能继续占着内存

`ThreadLocalMap` 并不是在 GC 一发生就立刻全量清理 stale entry。它通常是在后续执行 `get()`、`set()`、`remove()` 等操作时，顺手做一部分清理；或者等线程结束后，整张 map 一起被回收。

不过，并不是所有 `ThreadLocal` 内存泄漏都来自“临时 `ThreadLocal` 对象被 GC 后留下 stale entry”。更准确地说，常见问题有两类。

第一类是临时 `ThreadLocal`：

```java
void foo() {
    ThreadLocal<byte[]> local = new ThreadLocal<>();
    local.set(new byte[10 * 1024 * 1024]);
}
```

方法结束后，`local` 没有强引用了，key 可能被 GC 清成 `null`，但 value 还可能暂时留在线程的 `ThreadLocalMap` 里。这就是典型的 stale entry。

第二类是更常见的工程写法：`ThreadLocal` 是 `static final`，key 不会被 GC 清掉，但 value 忘了 `remove()`：

```java
private static final ThreadLocal<UserContext> CTX = new ThreadLocal<>();

void handle(Request req) {
    CTX.set(req.userContext());
    // 忘了 CTX.remove()
}
```

在线程池中，工作线程会被复用，线程自己的 `ThreadLocalMap` 也会继续存在。此时 entry 不是 stale entry：

```text
Entry
  key   = CTX
  value = 上一次请求的 UserContext
```

但它仍然可能是内存泄漏或上下文串用问题，因为请求已经结束，value 却还被长期存活的线程持有。

归根到底，很多时候泄漏的重点不是 `ThreadLocal` key 本身，而是 value 的生命周期被线程拉长了。

所以，弱引用 key 只能做到：

- 不让“已经没人引用的 `ThreadLocal` 对象”被永久保活

但它做不到：

- 在 key 消失的瞬间，自动无条件释放对应的 value
- 在 `static final ThreadLocal` 场景下，替你判断某个 value 的业务作用域已经结束

这就是为什么 `ThreadLocal` 的弱引用设计不能替代 `remove()`。

---

## 5. 为什么在线程池里必须 `remove()`

线程池场景下，工作线程通常会反复复用。也就是说：

- 一次请求把上下文放进 `ThreadLocal`
- 请求结束后线程没有销毁
- 后续另一个请求还会复用同一条线程

如果你没有清理，就会有两个风险。

### 5.1 上下文串用

后面的任务可能读到前一个任务留下的脏数据，例如：

- 用户 ID
- 租户 ID
- traceId
- 数据源路由信息

这属于功能错误。

### 5.2 value 长时间滞留在线程上

即使某个 `ThreadLocal` key 后来被 GC 清掉了，对应 value 也可能先作为 stale entry 留在线程里，直到后续某次 map 清理或线程结束。

这属于资源滞留/内存泄漏风险。

因此在线程池、Web 请求、RPC 过滤器、消息消费线程这类场景中，标准写法应该是：

```java
try {
    CTX.set(userContext);
    // do business
} finally {
    CTX.remove();
}
```

这里的 `remove()` 不是可有可无的“优化”，而是生命周期管理的一部分。

要特别区分：

- `set(null)`：通常只是把 value 设为 `null`，entry 还在
- `remove()`：删除当前线程里这个 `ThreadLocal` 的映射，更符合清理意图

所以如果你的目标是“这次任务结束后不再保留这个槽位”，优先用 `remove()`，不要拿 `set(null)` 代替。

---

## 6. 哪些用法是稳妥的，哪些用法容易出问题

### 6.1 稳妥用法

```java
private static final ThreadLocal<RequestContext> CTX = new ThreadLocal<>();

public void handle(RequestContext ctx) {
    try {
        CTX.set(ctx);
        // ...
    } finally {
        CTX.remove();
    }
}
```

这个写法有几个特点：

- `ThreadLocal` 实例本身有稳定强引用，不会无缘无故被 GC 清掉
- 值只在明确的作用域内存在
- 作用域结束后立刻清理

### 6.2 容易出问题的写法

```java
public void handle() {
    ThreadLocal<byte[]> local = new ThreadLocal<>();
    local.set(new byte[10 * 1024 * 1024]);
}
```

这里的问题是：

- 方法结束后，`local` 很快就没有强引用了
- key 可能被 GC 清掉
- 但 10MB 的 value 可能还留在线程里
- 如果线程来自线程池，这个残留时间可能很长

这就是“key 弱引用不等于 value 自动安全释放”的典型例子。

---

## 7. 一句话把这个问题说透

可以直接记下面这句：

- **`ThreadLocal` 的 key 用弱引用，是为了避免 `ThreadLocal` 实例本身被长期线程无意义地保活；它不会破坏正常使用，但也不能替代 `remove()`。**

再压缩一点就是：

- **只要你还持有 `ThreadLocal`，正常用法不受影响；真正的风险在于线程池里的 stale entry 和未清理 value。**

---

## 8. 实战建议

- 把 `ThreadLocal` 定义成可稳定持有的字段，典型是 `private static final`
- 把它当“上下文槽位”用，不要当跨任务缓存容器滥用
- 在线程池场景里，用 `try/finally + remove()` 管理生命周期
- 不要把 “key 是弱引用” 误解成 “value 会自动安全释放”
- 在虚拟线程场景里，承载上下文通常是合理的；但把昂贵、可变对象缓存进 `ThreadLocal` 往往不是好主意，可结合 [virtual-threads.md](./virtual-threads.md) 一起看

如果只记一个结论，就记：

- **弱引用 key 解决的是一部分可达性问题；`remove()` 解决的才是你的业务生命周期问题。**
