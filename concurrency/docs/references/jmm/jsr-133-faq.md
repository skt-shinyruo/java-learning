# JSR 133 (Java Memory Model) FAQ

Jeremy Manson and Brian Goetz, February 2004

> Cleaned local Markdown mirror of the upstream HTML page. Content is kept close to the original; only formatting and link normalization were applied.
>
> Upstream URL: [https://www.cs.umd.edu/~pugh/java/memoryModel/jsr-133-faq.html](https://www.cs.umd.edu/~pugh/java/memoryModel/jsr-133-faq.html)  
> Original local mirror: [jsr-133-faq.html](./jsr-133-faq.html)

以上说明的意思是：这份文档是对上游 HTML FAQ 的本地 Markdown 整理版，内容尽量贴近原文，只做了格式清理和链接规范化。下面保留英文原文，并按 `redis/docs/listpack.md` 的方式补充中文译文与解释。

## Table of Contents

- [What is a memory model, anyway?](#what-is-a-memory-model-anyway)
- [Do other languages, like C++, have a memory model?](#do-other-languages-like-c-have-a-memory-model)
- [What is JSR 133 about?](#what-is-jsr-133-about)
- [What is meant by reordering?](#what-is-meant-by-reordering)
- [What was wrong with the old memory model?](#what-was-wrong-with-the-old-memory-model)
- [What do you mean by incorrectly synchronized?](#what-do-you-mean-by-incorrectly-synchronized)
- [What does synchronization do?](#what-does-synchronization-do)
- [How can final fields appear to change their values?](#how-can-final-fields-appear-to-change-their-values)
- [How do final fields work under the new JMM?](#how-do-final-fields-work-under-the-new-jmm)
- [What does volatile do?](#what-does-volatile-do)
- [Does the new memory model fix the "double-checked locking"
problem?](#does-the-new-memory-model-fix-the-double-checked-locking-problem)
- [What if I'm writing a VM?](#what-if-im-writing-a-vm)
- [Why should I care?](#why-should-i-care)

下文依次讨论内存模型是什么、JSR 133 修复了什么、重排序与 happens-before 的含义、`final` / `volatile` / `synchronized` 在新 JMM 下的语义变化，以及为什么普通开发者和 JVM 实现者都应该关心这些规则。

<a id="what-is-a-memory-model-anyway"></a>
## What is a memory model, anyway?
In multiprocessor systems, processors generally have one or more layers of memory cache, which improves performance both by speeding access to data (because the data is closer to the processor) and reducing traffic on the shared memory bus (because many memory operations can be satisfied by local caches.) Memory caches can improve performance tremendously, but they present a host of new challenges. What, for example, happens when two processors examine the same memory location at the same time? Under what conditions will they see the same value?

在多处理器系统里，CPU 往往会有多层缓存。缓存能显著提升性能，因为数据离处理器更近，而且很多内存操作可以直接在本地缓存满足，从而减少共享总线流量。但缓存也带来了一个核心问题：如果两个处理器同时观察同一块内存，它们什么时候会看到同一个值？什么时候又可能看到不同的值？

At the processor level, a memory model defines necessary and sufficient conditions for knowing that writes to memory by other processors are visible to the current processor, and writes by the current processor are visible to other processors. Some processors exhibit a strong memory model, where all processors see exactly the same value for any given memory location at all times. Other processors exhibit a weaker memory model, where special instructions, called memory barriers, are required to flush or invalidate the local processor cache in order to see writes made by other processors or make writes by this processor visible to others. These memory barriers are usually performed when lock and unlock actions are taken; they are invisible to programmers in a high level language.

从硬件语义上说，内存模型就是在回答“一个处理器的写，何时对另一个处理器可见；当前处理器的写，何时能被别人看见”。有的体系结构内存模型较强，几乎总能让所有处理器看到一致的内存状态；有的体系结构更弱，需要借助 memory barrier 把本地缓存刷新或失效，才能看到其他处理器的写入，或者把自己的写入发布出去。程序员在高级语言里通常看不到这些 barrier，它们往往隐藏在加锁和解锁动作背后。

It can sometimes be easier to write programs for strong memory models, because of the reduced need for memory barriers. However, even on some of the strongest memory models, memory barriers are often necessary; quite frequently their placement is counterintuitive. Recent trends in processor design have encouraged weaker memory models, because the relaxations they make for cache consistency allow for greater scalability across multiple processors and larger amounts of memory.

The issue of when a write becomes visible to another thread is compounded by the compiler's reordering of code. For example, the compiler might decide that it is more efficient to move a write operation later in the program; as long as this code motion does not change the program's semantics, it is free to do so. If a compiler defers an operation, another thread will not see it until it is performed; this mirrors the effect of caching.

Moreover, writes to memory can be moved earlier in a program; in this case, other threads might see a write before it actually "occurs" in the program. All of this flexibility is by design -- by giving the compiler, runtime, or hardware the flexibility to execute operations in the optimal order, within the bounds of the memory model, we can achieve higher performance.

弱内存模型并不是“设计失误”，而是为了给编译器、运行时和硬件更多重排与优化空间，从而换取更好的扩展性和性能。除了缓存以外，编译器本身也会做重排序：只要不改变单线程语义，它就可以把某个写操作往后挪，甚至往前提。这意味着在多线程环境下，另一个线程看到的操作顺序，未必等同于源码书写顺序。

A simple example of this can be seen in the following code:

    Class Reordering {
      int x = 0, y = 0;
      public void writer() {
        x = 1;
        y = 2;
      }

      public void reader() {
        int r1 = y;
        int r2 = x;
      }
    }

Let's say that this code is executed in two threads concurrently, and the read of y sees the value 2. Because this write came after the write to x, the programmer might assume that the read of x must see the value 1. However, the writes may have been reordered. If this takes place, then the write to y could happen, the reads of both variables could follow, and then the write to x could take place. The result would be that r1 has the value 2, but r2 has the value 0.

上面的 `Reordering` 例子就是典型场景。如果读线程先读到 `y == 2`，很多人会直觉认为它随后一定读到 `x == 1`；但在重排序存在时，这个推断并不成立，完全可能出现 `r1 == 2` 而 `r2 == 0`。

The Java Memory Model describes what behaviors are legal in multithreaded code, and how threads may interact through memory. It describes the relationship between variables in a program and the low-level details of storing and retrieving them to and from memory or registers in a real computer system. It does this in a way that can be implemented correctly using a wide variety of hardware and a wide variety of compiler optimizations.

Java includes several language constructs, including volatile, final, and synchronized, which are intended to help the programmer describe a program's concurrency requirements to the compiler. The Java Memory Model defines the behavior of volatile and synchronized, and, more importantly, ensures that a correctly synchronized Java program runs correctly on all processor architectures.

Java Memory Model（JMM）要做的，就是定义这些行为里哪些是合法的、线程究竟如何通过内存交互，以及 `volatile`、`final`、`synchronized` 这些语言构件各自提供什么并发保证。更重要的是，JMM 试图保证：只要一个 Java 程序被“正确同步”，它就应该能在各种处理器架构上得到正确结果。

<a id="do-other-languages-like-c-have-a-memory-model"></a>
## Do other languages, like C++, have a memory model?
Most other programming languages, such as C and C++, were not designed with direct support for multithreading. The protections that these languages offer against the kinds of reorderings that take place in compilers and architectures are heavily dependent on the guarantees provided by the threading libraries used (such as pthreads), the compiler used, and the platform on which the code is run.

像 C、C++ 这样的很多传统语言，在最初设计时并没有把多线程当作语言级核心能力。因此，它们面对编译器重排和硬件重排时能提供多强的保护，很大程度取决于具体使用的线程库、编译器和运行平台。换句话说，内存模型并不是这些语言早期语义里天然完整的一部分。

<a id="what-is-jsr-133-about"></a>
## What is JSR 133 about?
Since 1997, several serious flaws have been discovered in the Java Memory Model as defined in Chapter 17 of the Java Language Specification. These flaws allowed for confusing behaviors (such as final fields being observed to change their value) and undermined the compiler's ability to perform common optimizations.

The Java Memory Model was an ambitious undertaking; it was the first time that a programming language specification attempted to incorporate a memory model which could provide consistent semantics for concurrency across a variety of architectures. Unfortunately, defining a memory model which is both consistent and intuitive proved far more difficult than expected. JSR 133 defines a new memory model for the Java language which fixes the flaws of the earlier memory model. In order to do this, the semantics of final and volatile needed to change.

The full semantics are available at _[http://www.cs.umd.edu/users/pugh/java/memoryModel](https://www.cs.umd.edu/users/pugh/java/memoryModel)_ , but the formal semantics are not for the timid. It is surprising, and sobering, to discover how complicated seemingly simple concepts like synchronization really are. Fortunately, you need not understand the details of the formal semantics -- the goal of JSR 133 was to create a set of formal semantics that provides an intuitive framework for how volatile, synchronized, and final work.

The goals of JSR 133 include:

  - Preserving existing safety guarantees, like type-safety, and strengthening others. For example, variable values may not be created "out of thin air": each value for a variable observed by some thread must be a value that can reasonably be placed there by some thread.
  - The semantics of correctly synchronized programs should be as simple and intuitive as possible.
  - The semantics of incompletely or incorrectly synchronized programs should be defined so that potential security hazards are minimized.
  - Programmers should be able to reason confidently about how multithreaded programs interact with memory.
  - It should be possible to design correct, high performance JVM implementations across a wide range of popular hardware architectures.
  - A new guarantee of _initialization safety_ should be provided. If an object is properly constructed (which means that references to it do not escape during construction), then all threads which see a reference to that object will also see the values for its final fields that were set in the constructor, without the need for synchronization.
  - There should be minimal impact on existing code.

JSR 133 的背景，是旧版 JMM 自 1997 年以来暴露出的多处严重缺陷。它们既会导致让人困惑的行为，例如 `final` 字段竟然可能“变值”，也会妨碍编译器做本来非常常见的优化。Java 当年试图做一件相当有野心的事：第一次在编程语言规范层面，给出一个能跨多种硬件架构保持一致并发语义的内存模型。但实践证明，要同时做到“语义自洽”与“程序员直觉可理解”，比最初想象的难得多。

JSR 133 就是在这种背景下对旧 JMM 的系统修订。为了修复原有缺陷，`final` 和 `volatile` 的语义都必须调整。完整的形式化语义可以在上面的链接中找到，但它并不是轻松阅读材料；JSR 133 的真正目标，是通过那套严格的形式化定义，给程序员提供一套更直观、更能解释 `volatile`、`synchronized` 和 `final` 行为的框架。

从中文角度概括，JSR 133 希望达到这些目标：

- 保留既有的安全保证，例如类型安全，同时补强其他安全性质；例如变量的值不能“凭空产生（out of thin air）”，线程观察到的值必须能合理追溯到某个线程的真实写入。
- 让“正确同步”的程序拥有尽可能简单、直观的语义。
- 即便程序同步不完整或写错了，也要尽量把潜在安全风险压到最低。
- 让程序员能够更有把握地推理多线程程序与内存的交互方式。
- 让 JVM 能在广泛的主流硬件架构上既正确又高性能地实现。
- 引入新的“初始化安全（initialization safety）”保证：如果对象被正确构造，并且构造期间引用没有逃逸出去，那么其他线程一旦拿到该对象引用，就能看到构造函数里写入的 `final` 字段值，而不必再额外同步。
- 对已有代码的影响要尽可能小。

<a id="what-is-meant-by-reordering"></a>
## What is meant by reordering?
There are a number of cases in which accesses to program variables (object instance fields, class static fields, and array elements) may appear to execute in a different order than was specified by the program. The compiler is free to take liberties with the ordering of instructions in the name of optimization. Processors may execute instructions out of order under certain circumstances. Data may be moved between registers, processor caches, and main memory in different order than specified by the program.

For example, if a thread writes to field `a` and then to field `b`, and the value of `b` does not depend on the value of `a`, then the compiler is free to reorder these operations, and the cache is free to flush `b` to main memory before `a`. There are a number of potential sources of reordering, such as the compiler, the JIT, and the cache.

The compiler, runtime, and hardware are supposed to conspire to create the illusion of as-if-serial semantics, which means that in a single-threaded program, the program should not be able to observe the effects of reorderings. However, reorderings can come into play in incorrectly synchronized multithreaded programs, where one thread is able to observe the effects of other threads, and may be able to detect that variable accesses become visible to other threads in a different order than executed or specified in the program.

Most of the time, one thread doesn't care what the other is doing. But when it does, that's what synchronization is for.

所谓 reordering，就是程序里对变量的访问在“对外可观察”的效果上，看起来并不是按源码书写顺序执行的。它的来源很多：编译器为了优化会调整指令顺序，JIT 会做同样的事，处理器本身也可能乱序执行，数据在寄存器、CPU cache 和主内存之间流动时，同样不一定严格遵守源码顺序。

例如，一个线程先写 `a` 再写 `b`，如果 `b` 的值不依赖 `a`，那么编译器完全可以重排这两个写；即便编译器不动，缓存系统也可能先把 `b` 刷回主内存。单线程程序之所以通常感觉不到这些变化，是因为编译器、运行时和硬件共同努力维持“仿佛串行（as-if-serial）”的假象，也就是只要你站在单线程视角看，结果应该和按顺序执行一致。

问题出在多线程，尤其是同步不正确的多线程程序里。一个线程开始观察另一个线程的效果时，就可能发现某些变量对外“变得可见”的顺序与源码顺序不同。大多数时候，一个线程根本不关心另一个线程此刻做了什么；一旦它关心，这就是同步机制存在的意义。

<a id="what-was-wrong-with-the-old-memory-model"></a>
## What was wrong with the old memory model?
There were several serious problems with the old memory model. It was difficult to understand, and therefore widely violated. For example, the old model did not, in many cases, allow the kinds of reorderings that took place in every JVM. This confusion about the implications of the old model was what compelled the formation of JSR-133.

One widely held belief, for example, was that if final fields were used, then synchronization between threads was unnecessary to guarantee another thread would see the value of the field. While this is a reasonable assumption and a sensible behavior, and indeed how we would want things to work, under the old memory model, it was simply not true. Nothing in the old memory model treated final fields differently from any other field -- meaning synchronization was the only way to ensure that all threads see the value of a final field that was written by the constructor. As a result, it was possible for a thread to see the default value of the field, and then at some later time see its constructed value. This means, for example, that immutable objects like String can appear to change their value -- a disturbing prospect indeed.

The old memory model allowed for volatile writes to be reordered with nonvolatile reads and writes, which was not consistent with most developers intuitions about volatile and therefore caused confusion.

Finally, as we shall see, programmers' intuitions about what can occur when their programs are incorrectly synchronized are often mistaken. One of the goals of JSR-133 is to call attention to this fact.

旧内存模型的问题不止一个，而且都很严重。首先，它本身就很难理解，所以程序员经常无意间违反它。更糟的是，旧模型在很多场景下甚至不允许现实中每个 JVM 都会做的那些重排优化，这让“规范怎么说”和“实现怎么做”之间出现了很大的认知裂缝，也直接推动了 JSR 133 的产生。

一个非常典型的误解是：很多人以为只要字段是 `final`，就不需要额外同步，其他线程自然能看见构造函数里写进去的值。这个直觉完全合理，也确实是我们希望语言提供的行为，但在旧 JMM 下它并不成立。旧模型没有把 `final` 和普通字段区别对待，所以如果没有同步，别的线程先看到默认值、过一会儿又看到“真正构造后的值”都是可能的。于是像 `String` 这种不可变对象，都可能表现出“值变化了”的诡异现象。

同时，旧模型还允许 `volatile` 写与非 `volatile` 的读写发生重排，这和大多数开发者对 `volatile` 的直觉严重不一致，因此制造了更多混乱。最后，JSR 133 还想强调一点：程序员对于“错误同步时到底可能发生什么”往往远远低估，很多看似不可能的结果，事实上都是允许出现的。

<a id="what-do-you-mean-by-incorrectly-synchronized"></a>
## What do you mean by incorrectly synchronized?
Incorrectly synchronized code can mean different things to different people. When we talk about incorrectly synchronized code in the context of the Java Memory Model, we mean any code where

  1. there is a write of a variable by one thread,
  2. there is a read of the same variable by another thread and
  3. the write and read are not ordered by synchronization

When these rules are violated, we say we have a _data race_ on that variable. A program with a data race is an incorrectly synchronized program.

“错误同步”这个词在不同人口中可能含义不同；但在 JMM 语境里，它的定义其实很直接：只要同时满足下面三件事，就属于同步不正确的代码。

1. 某个线程写了一个变量。
2. 另一个线程读了同一个变量。
3. 这个写和这个读之间，没有被任何同步关系排序起来。

一旦满足这三个条件，就说这个变量上发生了 _data race_（数据竞争）。包含数据竞争的程序，就是 JMM 所说的 incorrectly synchronized program。

<a id="what-does-synchronization-do"></a>
## What does synchronization do?
Synchronization has several aspects. The most well-understood is mutual exclusion -- only one thread can hold a monitor at once, so synchronizing on a monitor means that once one thread enters a synchronized block protected by a monitor, no other thread can enter a block protected by that monitor until the first thread exits the synchronized block.

同步最容易理解的一面，是互斥：同一时刻只有一个线程能持有某个 monitor（监视器），所以只要一个线程进入了由该 monitor 保护的 `synchronized` 代码块，其他线程就必须等它退出后才能进入。

But there is more to synchronization than mutual exclusion. Synchronization ensures that memory writes by a thread before or during a synchronized block are made visible in a predictable manner to other threads which synchronize on the same monitor. After we exit a synchronized block, we **release** the monitor, which has the effect of flushing the cache to main memory, so that writes made by this thread can be visible to other threads. Before we can enter a synchronized block, we **acquire** the monitor, which has the effect of invalidating the local processor cache so that variables will be reloaded from main memory. We will then be able to see all of the writes made visible by the previous release.

同步更重要的一面，是可见性与顺序保证。一个线程在 `synchronized` 块之前或之内做的写入，会以可预期的方式对那些“在同一个 monitor 上同步”的其他线程可见。退出同步块时，线程对 monitor 做 **release**；进入同步块前，线程需要 **acquire** 这个 monitor。用缓存语言来描述，就是 release 让本线程的写入有机会被发布出去，acquire 让当前线程放弃本地旧视图、重新获取可见数据。

Discussing this in terms of caches, it may sound as if these issues only affect multiprocessor machines. However, the reordering effects can be easily seen on a single processor. It is not possible, for example, for the compiler to move your code before an acquire or after a release. When we say that acquires and releases act on caches, we are using shorthand for a number of possible effects.

不过，这种“刷缓存/失效缓存”的说法只是简写，它背后对应的是编译器、运行时和硬件共同施加的一系列约束，并不只发生在多处理器机器上；即便单处理器环境里，编译器同样不能随意把代码挪到 acquire 之前或 release 之后。

The new memory model semantics create a partial ordering on memory operations (read field, write field, lock, unlock) and other thread operations (start and join), where some actions are said to _happen before_ other operations. When one action happens before another, the first is guaranteed to be ordered before and visible to the second. The rules of this ordering are as follows:

新 JMM 用 _happens-before_ 来描述这种顺序与可见性关系。只要动作 A happens-before 动作 B，就意味着 A 一定排在 B 之前，并且 A 的结果对 B 可见。上面的规则可以翻成更直白的中文：

  - Each action in a thread happens before every action in that thread that comes later in the program's order.
  - An unlock on a monitor happens before every subsequent lock on **that same** monitor.
  - A write to a volatile field happens before every subsequent read of **that same** volatile.
  - A call to `start()` on a thread happens before any actions in the started thread.
  - All actions in a thread happen before any other thread successfully returns from a `join() `on that thread.

- 同一个线程里，程序顺序靠前的动作 happens-before 程序顺序靠后的动作。
- 对某个 monitor 的一次 `unlock`，happens-before 后续对**同一个** monitor 的每一次 `lock`。
- 对某个 `volatile` 字段的一次写，happens-before 后续对**同一个** `volatile` 字段的每一次读。
- 对线程调用 `start()`，happens-before 被启动线程中的任何动作。
- 某线程中的所有动作，都 happens-before 另一个线程从对它执行的 `join()` 成功返回之后的动作。

This means that any memory operations which were visible to a thread before exiting a synchronized block are visible to any thread after it enters a synchronized block protected by the same monitor, since all the memory operations happen before the release, and the release happens before the acquire.

所以，如果线程 A 在退出某个 `synchronized` 块前能看见某些写入，那么线程 B 在进入由同一 monitor 保护的 `synchronized` 块后，也应该能看见这些写入。

Another implication is that the following pattern, which some people use to force a memory barrier, doesn't work:

另一个常见误区是，有人会试图用下面这种写法“强行打一记 barrier”，但它其实不成立：

    synchronized (new Object()) {}

This is actually a no-op, and your compiler can remove it entirely, because the compiler knows that no other thread will synchronize on the same monitor. You have to set up a happens-before relationship for one thread to see the results of another.

这实际上是个 no-op，编译器完全可以把它删掉，因为它知道不会有其他线程在同一个 monitor 上同步。想让一个线程看见另一个线程的结果，必须真的建立 happens-before 关系。

**Important Note:** Note that it is important for both threads to synchronize on the same monitor in order to set up the happens-before relationship properly. It is not the case that everything visible to thread A when it synchronizes on object X becomes visible to thread B after it synchronizes on object Y. The release and acquire have to "match" (i.e., be performed on the same monitor) to have the right semantics. Otherwise, the code has a data race.

这里还有一个关键点：两个线程必须在**同一个** monitor 上同步，才能正确建立 happens-before。并不是说线程 A 在对象 X 上同步时能看见的一切，都会在线程 B 随后对对象 Y 同步时自动可见。release 和 acquire 必须在同一个 monitor 上“配对”，否则程序仍然存在 data race。

<a id="how-can-final-fields-appear-to-change-their-values"></a>
## How can final fields appear to change their values?
One of the best examples of how final fields' values can be seen to change involves one particular implementation of the `String` class.

A `String` can be implemented as an object with three fields -- a character array, an offset into that array, and a length. The rationale for implementing `String` this way, instead of having only the character array, is that it lets multiple `String` and `StringBuffer` objects share the same character array and avoid additional object allocation and copying. So, for example, the method `String.substring()` can be implemented by creating a new string which shares the same character array with the original `String` and merely differs in the length and offset fields. For a `String`, these fields are all final fields.

`final` 字段看起来“变值”的经典例子，来自早期 `String` 的一种实现方式。一个 `String` 对象可以由三部分组成：字符数组、数组中的偏移量（offset）以及长度（length）。这样设计的好处是，不同的 `String` 或 `StringBuffer` 可以共享同一个底层字符数组，避免额外分配和复制。于是像 `substring()` 这样的操作，只需要创建一个新的字符串对象，让它与原始字符串共享字符数组，再调整 offset 和 length 即可；而这些字段在 `String` 中通常都是 `final`。

    String s1 = "/usr/tmp";
    String s2 = s1.substring(4);

The string `s2` will have an offset of 4 and a length of 4. But, under the old model, it was possible for another thread to see the offset as having the default value of 0, and then later see the correct value of 4, it will appear as if the string "/usr" changes to "/tmp".

按照正常直觉，`s2` 的 offset 应该是 4、length 也是 4，因此它代表的是 `"/tmp"`。但在旧 JMM 下，另一个线程完全可能先看到 offset 的默认值 0，过一会儿又看到它真正的值 4，于是这个线程眼里的 `s2` 似乎先是 `"/usr"`，后来又变成了 `"/tmp"`。

The original Java Memory Model allowed this behavior; several JVMs have exhibited this behavior. The new Java Memory Model makes this illegal.

原始 Java 内存模型允许这种行为，而且不止一个 JVM 曾真的表现出这种现象；新 JMM 则明确把它判定为非法。

<a id="how-do-final-fields-work-under-the-new-jmm"></a>
## How do final fields work under the new JMM?
The values for an object's final fields are set in its constructor. Assuming the object is constructed "correctly", once an object is constructed, the values assigned to the final fields in the constructor will be visible to all other threads without synchronization. In addition, the visible values for any other object or array referenced by those final fields will be at least as up-to-date as the final fields.

新 JMM 对 `final` 给出的核心保证是：只要对象被“正确构造”，那么构造函数里为 `final` 字段赋的值，在对象构造完成后就会对所有线程可见，而且不需要额外同步。更进一步，如果某个 `final` 字段本身还是一个对象引用或数组引用，那么通过这个 `final` 引用能看到的对象内容，至少也会和构造结束时一样“新”。

What does it mean for an object to be properly constructed? It simply means that no reference to the object being constructed is allowed to "escape" during construction. (See [Safe Construction Techniques](http://www-106.ibm.com/developerworks/java/library/j-jtp0618.html) for examples.) In other words, do not place a reference to the object being constructed anywhere where another thread might be able to see it; do not assign it to a static field, do not register it as a listener with any other object, and so on. These tasks should be done after the constructor completes, not in the constructor.

所谓“正确构造”，关键要求只有一个：对象在构造过程中不能发生引用逃逸。也就是 `this` 不能在构造函数执行期间被其他线程看到。你不应该在构造函数里把它塞进静态字段、注册成监听器，或者以任何其他方式暴露给外界；这些动作都应该在构造完成之后再做。

    class FinalFieldExample {
      final int x;
      int y;
      static FinalFieldExample f;
      public FinalFieldExample() {
        x = 3;
        y = 4;
      }

      static void writer() {
        f = new FinalFieldExample();
      }

      static void reader() {
        if (f != null) {
          int i = f.x;
          int j = f.y;
        }
      }
    }

The class above is an example of how final fields should be used. A thread executing `reader` is guaranteed to see the value 3 for `f.x`, because it is final. It is not guaranteed to see the value 4 for `y`, because it is not final. If `FinalFieldExample`'s constructor looked like this:

上面的类就是 `final` 字段的标准用法：`reader` 线程读取 `f.x` 时，一定能看到 `3`，因为 `x` 是 `final`；但不保证一定看到 `y == 4`，因为 `y` 不是 `final`。如果构造函数改成下面这样：

    public FinalFieldExample() { // bad!
      x = 3;
      y = 4;
      // bad construction - allowing this to escape
      global.obj = this;
    }

then threads that read the reference to `this `from `global.obj` are **not** guaranteed to see 3 for `x`.

那么，那些通过 `global.obj` 读到 `this` 引用的线程，就**不能**保证一定看到 `x == 3`。

The ability to see the correctly constructed value for the field is nice, but if the field itself is a reference, then you also want your code to see the up to date values for the object (or array) to which it points. If your field is a final field, this is also guaranteed. So, you can have a final pointer to an array and not have to worry about other threads seeing the correct values for the array reference, but incorrect values for the contents of the array. Again, by "correct" here, we mean "up to date as of the end of the object's constructor", not "the latest value available".

另一个常被忽略的点是：`final` 不只保证“字段自身的值”可见。如果一个 `final` 字段指向数组或其他对象，那么其他线程通过它观察到的内容，也应该至少和构造函数结束时一致。这里的“正确”不是指“全局最新值”，而是指“至少不早于构造完成时的状态”。

Now, having said all of this, if, after a thread constructs an immutable object (that is, an object that only contains final fields), you want to ensure that it is seen correctly by all of the other thread, you **still** typically need to use synchronization. There is no other way to ensure, for example, that the reference to the immutable object will be seen by the second thread. The guarantees the program gets from final fields should be carefully tempered with a deep and careful understanding of how concurrency is managed in your code.

不过，即便对象本身是不可变对象，想让“对象引用的发布”对其他线程可靠可见，通常仍然需要同步；`final` 解决的是构造完成后的字段语义，不会自动替你完成跨线程发布。

There is no defined behavior if you want to use JNI to change final fields.

至于用 JNI 去修改 `final` 字段，规范没有定义其行为。

<a id="what-does-volatile-do"></a>
## What does volatile do?
Volatile fields are special fields which are used for communicating state between threads. Each read of a volatile will see the last write to that volatile by any thread; in effect, they are designated by the programmer as fields for which it is never acceptable to see a "stale" value as a result of caching or reordering. The compiler and runtime are prohibited from allocating them in registers. They must also ensure that after they are written, they are flushed out of the cache to main memory, so they can immediately become visible to other threads. Similarly, before a volatile field is read, the cache must be invalidated so that the value in main memory, not the local processor cache, is the one seen. There are also additional restrictions on reordering accesses to volatile variables.

`volatile` 字段的主要用途，是在线程之间传递状态。对某个 `volatile` 的每次读取，都应该看见任意线程对它的最新一次写入；换句话说，程序员把它显式标记为“这里不允许读到因缓存或重排造成的陈旧值”。因此编译器和运行时不能把它悄悄长期放在寄存器里，也必须保证写入后能尽快对其他线程可见，读取前则不能只依赖本地缓存旧值。另外，JMM 还会对 `volatile` 周边的读写重排施加额外限制。

Under the old memory model, accesses to volatile variables could not be reordered with each other, but they could be reordered with nonvolatile variable accesses. This undermined the usefulness of volatile fields as a means of signaling conditions from one thread to another.

旧内存模型的问题在于：它虽然不允许 `volatile` 之间彼此重排，却仍然允许 `volatile` 访问和普通字段访问交错重排，这大大削弱了 `volatile` 作为线程间信号机制的价值。

Under the new memory model, it is still true that volatile variables cannot be reordered with each other. The difference is that it is now no longer so easy to reorder normal field accesses around them. Writing to a volatile field has the same memory effect as a monitor release, and reading from a volatile field has the same memory effect as a monitor acquire. In effect, because the new memory model places stricter constraints on reordering of volatile field accesses with other field accesses, volatile or not, anything that was visible to thread A when it writes to volatile field `f` becomes visible to thread B when it reads `f`.

新 JMM 则强化了它的语义：对 `volatile` 的写，拥有类似 monitor `release` 的内存效果；对 `volatile` 的读，拥有类似 monitor `acquire` 的内存效果。因此，线程 A 在写 `volatile f` 时可见的一切，在正确的 happens-before 关系下，也会在线程 B 读到同一个 `volatile f` 时变得可见。

Here is a simple example of how volatile fields can be used:

下面给一个简单例子：

    class VolatileExample {
      int x = 0;
      volatile boolean v = false;
      public void writer() {
        x = 42;
        v = true;
      }

      public void reader() {
        if (v == true) {
          //uses x - guaranteed to see 42.
        }
      }
    }

Assume that one thread is calling `writer`, and another is calling `reader`. The write to `v` in `writer` releases the write to `x` to memory, and the read of `v` acquires that value from memory. Thus, if the reader sees the value ` true` for v, it is also guaranteed to see the write to 42 that happened before it. This would not have been true under the old memory model. If `v` were not volatile, then the compiler could reorder the writes in `writer`, and `reader`'s read of `x` might see 0.

这个 `VolatileExample` 就是经典发布模式：写线程先把普通字段 `x` 设为 `42`，再把 `volatile` 标志 `v` 设为 `true`；读线程一旦读到 `v == true`，就必须也能看见 `x == 42`。如果 `v` 不是 `volatile`，那么编译器就可能把 `writer()` 中的写入重排，读线程也就可能读到 `v == true` 却仍然看到 `x == 0`。

Effectively, the semantics of volatile have been strengthened substantially, almost to the level of synchronization. Each read or write of a volatile field acts like "half" a synchronization, for purposes of visibility.

所以可以把 `volatile` 理解成“半个同步”：它不像 `synchronized` 那样提供互斥，但在可见性与有序性上已经非常强。

**Important Note:** Note that it is important for both threads to access the same volatile variable in order to properly set up the happens-before relationship. It is not the case that everything visible to thread A when it writes volatile field `f` becomes visible to thread B after it reads volatile field `g`. The release and acquire have to "match" (i.e., be performed on the same volatile field) to have the right semantics.

不过这里有个限制一定要记住：要建立正确的 happens-before，两个线程必须访问的是**同一个** `volatile` 变量。线程 A 写 `f`，并不会自动让线程 B 读取 `g` 之后看到 A 当时可见的一切；和 monitor 一样，release / acquire 必须在同一个同步媒介上“配对”才有意义。

## Does the new memory model fix the "double-checked locking" problem?
The (infamous) double-checked locking idiom (also called the multithreaded singleton pattern) is a trick designed to support lazy initialization while avoiding the overhead of synchronization. In very early JVMs, synchronization was slow, and developers were eager to remove it -- perhaps too eager. The double-checked locking idiom looks like this:

双重检查锁定（double-checked locking, DCL）是并发领域最著名的“聪明反被聪明误”技巧之一。它的目标是做懒初始化，同时避开每次都进入同步块的成本。在早期 JVM 里，`synchronized` 的开销曾经比较高，所以开发者很自然会想把它从“常见快路径”里拿掉，于是就有了上面的写法：先在锁外判断一次，再在锁内判断一次。

    // double-checked-locking - don't do this!

    private static Something instance = null;

    public Something getInstance() {
      if (instance == null) {
        synchronized (this) {
          if (instance == null)
            instance = new Something();
        }
      }
      return instance;
    }

This looks awfully clever -- the synchronization is avoided on the common code path. There's only one problem with it -- **it doesn't work**. Why not? The most obvious reason is that the writes which initialize `instance` and the write to the `instance` field can be reordered by the compiler or the cache, which would have the effect of returning what appears to be a partially constructed `Something`. The result would be that we read an uninitialized object. There are lots of other reasons why this is wrong, and why algorithmic corrections to it are wrong. There is no way to fix it using the old Java memory model. More in-depth information can be found at [Double-checked locking: Clever, but broken](http://www.javaworld.com/jw-02-2001/jw-0209-double.html) and [The "Double Checked Locking is broken" declaration](https://www.cs.umd.edu/~pugh/java/memoryModel/DoubleCheckedLocking.html)

问题在于，这套写法在旧 JMM 下根本不可靠。最直接的原因就是：`instance = new Something()` 背后的“对象初始化”与“把引用写进 `instance` 字段”并不保证对其他线程以同样顺序可见，编译器和缓存系统都可能导致另一个线程先看到一个非 `null` 引用，再看到一个还没真正构造完成的对象。于是读线程表面上拿到了 `instance`，实际上读到的却是“半初始化对象”。这也是为什么旧 Java 内存模型下，DCL 没有办法被真正修好。

Many people assumed that the use of the `volatile `keyword would eliminate the problems that arise when trying to use the double-checked-locking pattern. In JVMs prior to 1.5, `volatile` would not ensure that it worked (your mileage may vary). Under the new memory model, making the `instance` field volatile will "fix" the problems with double-checked locking, because then there will be a happens-before relationship between the initialization of the `Something` by the constructing thread and the return of its value by the thread that reads it.

很多人曾以为给目标字段加上 `volatile` 就能解决一切，但在 Java 1.5 之前这也不成立，具体行为甚至会因 JVM 而异。到了新 JMM 里，情况才真正改变：如果把 `instance` 声明成 `volatile`，那么构造线程对 `Something` 的初始化结果，与读线程返回这个引用之间，就能建立可靠的 happens-before 关系，因此 DCL 才算在语义上被“修好”。

~~However, for fans of double-checked locking (and we really hope there are none left), the news is still not good. The whole point of double-checked locking was to avoid the performance overhead of synchronization. Not only has brief synchronization gotten a LOT less expensive since the Java 1.0 days, but under the new memory model, the performance cost of using volatile goes up, almost to the level of the cost of synchronization. So there's still no good reason to use double-checked-locking.~~_Redacted -- volatiles are cheap on most platforms._

~~不过，对于仍然迷恋 DCL 的人来说，原始 FAQ 曾紧接着补了一句坏消息：DCL 当初的初衷是省掉同步开销，而在新内存模型下，`volatile` 的成本上升，短暂同步本身又便宜了很多，所以依然没什么理由使用 DCL。~~_删节说明：在大多数平台上，`volatile` 实际上已经很便宜。_

Instead, use the Initialization On Demand Holder idiom, which is thread-safe and a lot easier to understand:

即便如此，FAQ 仍建议优先使用更简单、也更不容易写错的 Initialization On Demand Holder 写法：

    private static class LazySomethingHolder {
      public static Something something = new Something();
    }

    public static Something getInstance() {
      return LazySomethingHolder.something;
    }

This code is guaranteed to be correct because of the initialization guarantees for static fields; if a field is set in a static initializer, it is guaranteed to be made visible, correctly, to any thread that accesses that class.

这个模式依赖的是静态字段初始化的语言级保证：只要字段在静态初始化期间被设置，那么任何线程在访问该类时，都应该正确地看到它。这也是为什么 Holder 模式既线程安全，又比 DCL 更容易解释。

<a id="what-if-im-writing-a-vm"></a>
## What if I'm writing a VM?
You should look at [http://gee.cs.oswego.edu/dl/jmm/cookbook.html](https://gee.cs.oswego.edu/dl/jmm/cookbook.html).

如果你关心的是“作为 JVM / VM 实现者，到底该怎么把这些语义落到具体实现上”，那么应该直接去看上面的 JMM Cookbook。FAQ 在这里不展开实现细节，而是把实现者导向更底层、更操作性的资料。

<a id="why-should-i-care"></a>
## Why should I care?
Why should you care? Concurrency bugs are very difficult to debug. They often don't appear in testing, waiting instead until your program is run under heavy load, and are hard to reproduce and trap. You are much better off spending the extra effort ahead of time to ensure that your program is properly synchronized; while this is not easy, it's a lot easier than trying to debug a badly synchronized application.

为什么你应该关心这些？因为并发 bug 极难排查。它们常常在测试阶段完全不出现，偏偏在生产环境高负载时才暴露，而且非常难复现、难定位。与其事后花巨大代价去调试一个同步写坏了的应用，不如一开始就多花些精力确保程序被正确同步。虽然这件事本身也不轻松，但仍然远比追着并发故障跑要容易得多。
