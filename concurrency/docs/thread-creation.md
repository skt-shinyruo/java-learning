# Java 线程创建与启动：JVM 规范、HotSpot 实现与 JMM 语义

本文回答一个很常见的问题：

- 在 Java 里写下 `new Thread(...).start()` 时，对 JVM 来说到底发生了什么？

先给结论：

- `new Thread(...)` 主要是在堆里创建一个普通的 `Thread` 对象。
- 真正让 JVM 启动一个新的执行流的关键动作是 `thread.start()`。
- 如果只讨论传统 platform thread，这件事可以从三层理解：
  - **JVM 规范视角**：JVM 需要给线程准备哪些运行时数据区。
  - **HotSpot 实现视角**：HotSpot 怎样把 Java 线程对象落到内部线程结构和 OS 原生线程上。
  - **JMM 视角**：`start()` / `join()` 建立了什么 `happens-before` 语义。

如果你想先把运行时数据区看清楚，可以配合 `jvm/docs/jvm-memory.md`；如果你想先把并发中的可见性、有序性和 `happens-before` 主线补齐，可以先读 [jmm-notes.md](./jmm-notes.md)。本文只讨论 platform thread；虚拟线程可另看 [virtual-threads.md](./virtual-threads.md)。

---

## 1. 先区分 `new Thread()` 和 `start()`

很多人会把“创建线程”说成一件事，但更准确地讲，至少要拆成两步：

```java
Thread t = new Thread(task); // 1) 创建 Thread 对象
t.start();                   // 2) 启动线程
```

这两步的含义并不一样：

- `new Thread(task)`：创建一个普通 Java 对象，状态是 `NEW`
- `t.start()`：通知 JVM 真正启动一个新的线程执行流

所以，**`new` 只是“造对象”，`start()` 才是“启动线程”**。

---

## 2. JVM 规范视角：JVM 需要为线程准备什么

先强调一点：规范层面更关注“逻辑上必须有什么”，不强调具体 HotSpot 一定怎么实现。

### 2.1 `new Thread(...)` 时

在 Java 层执行 `new Thread(...)` 时，最核心的是：

- 在堆上分配一个 `Thread` 对象
- 初始化线程名、优先级、daemon 标记、目标 `Runnable` 等元数据
- 继承父线程的一些上下文，例如 `contextClassLoader`、`InheritableThreadLocal`
- 线程状态处于 `NEW`

这时还没有真正开始执行字节码，也没有新的线程栈开始跑起来。

### 2.2 `thread.start()` 时

当调用 `start()`，JVM 需要把“一个普通对象”变成“一个真的能执行字节码的线程上下文”。从规范视角，可以理解为 JVM 需要为这个线程建立线程私有运行时数据：

- **程序计数器（PC Register）**：记录当前线程接下来要执行的字节码位置
- **Java 虚拟机栈（JVM Stack）**：方法调用对应的一帧一帧栈帧
- **本地方法栈（Native Method Stack）**：如果执行 native 方法，需要对应的本地调用栈

但这里要注意一个很容易混淆的点：

- **这三类是 JVM 规范里的顶层运行时数据区分类，不是“线程上下文里所有细项的平铺列表”。**

也就是说，如果你问“从 JVM 规范的运行时数据区划分看，线程私有项有哪些”，那主答案就是这三类；但如果你继续追问“栈里到底还有什么”，那些细节并不是第四类、第五类，而是落在已有分类里面。

以 `Java Virtual Machine Stack` 为例，JVM 规范后面的 `§2.6 Frames` 又继续把它拆开了：

- 每次方法调用都会创建一个新的 frame
- frame 存放数据和中间结果，也参与动态链接、方法返回和异常分派
- 每个 frame 都有自己的：
  - local variables
  - operand stack
  - 指向当前方法所属类运行时常量池的引用

所以更准确地说：

- **局部变量表、操作数栈、动态链接、方法返回地址/返回值传递、异常分派相关信息，不是独立于 `JVM Stack` 的新类别，而是 frame 这一层的内容。**

`pc Register` 也一样，它表示的是当前线程当前方法的执行位置；规范还特别说明：

- 如果当前方法不是 `native`，`pc` 记录当前正在执行的 JVM 指令地址
- 如果当前方法是 `native`，`pc` 的值是未定义的

`Native Method Stack` 则还要再补一句：

- **它在 JVM 规范里本身就是实现相关的。**
- 规范写的是：JVM *may use* conventional stacks 来支持 `native` 方法。
- 也就是说，支持 native 的实现通常会有这一层，而且通常是 per-thread；但规范并不要求所有 JVM 都必须以某种统一的物理方式单独实现一块“本地方法栈”。

因此，把规范视角压缩成一句更完整的话，可以记成：

- **线程私有运行时数据区，按 JVMS 的顶层分类看主要就是 `pc Register`、`Java Virtual Machine Stack`、`Native Method Stack`；其中 frame 及其局部变量表/操作数栈等细节属于 `Java Virtual Machine Stack` 的内部内容，而 `Native Method Stack` 还带有实现相关色彩。**

同时，JVM 还会：

- 检查这个线程是否仍然处于 `NEW` 状态，防止重复启动
- 让线程进入可运行状态，等待底层调度
- 在线程真正被调度后，从 JVM 线程入口一路进入 `Thread.run()`

这里可以把规范层面理解成一句话：

- **JVM 要为每个线程准备独立的执行上下文，让它能单独执行字节码。**

规范依据：

- JVMS 17 `§2.5 Run-Time Data Areas`：per-thread data areas are created when a thread is created and destroyed when the thread terminates
- JVMS 17 `§2.5.1`：`pc Register`
- JVMS 17 `§2.5.2`：`Java Virtual Machine Stacks`
- JVMS 17 `§2.5.6`：`Native Method Stacks`
- JVMS 17 `§2.6`：`Frames`

官方链接：

- [JVMS 17 Chapter 2](https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-2.html)

### 2.3 线程运行和结束时

线程跑起来以后，JVM 会围绕这个线程私有上下文继续工作：

- 方法调用时在该线程自己的虚拟机栈上压入新栈帧
- 方法返回或异常抛出时弹出栈帧
- `run()` 正常结束或因未捕获异常退出后，线程状态变成 `TERMINATED`
- 线程私有运行时资源随后可被清理

---

## 3. HotSpot 实现视角：JVM 怎样把它变成真正的 OS 线程

如果换成 HotSpot 的实现视角，问题会更具体一些：不仅要有 Java 对象，还要有能被操作系统调度的真实线程。

### 3.1 `new Thread(...)` 只是 Java 对象

在 HotSpot 里，执行 `new Thread(...)` 时：

- 创建的是 Java 层的 `java.lang.Thread` 对象
- 目标 `Runnable`、线程名、优先级等信息先保存在这个对象里
- 此时还没有对应的 OS 原生线程开始运行

### 3.2 `start()` 会进入 JVM 的本地线程创建链路

下面这段只以本机 `openjdk version "17.0.18"` 对应的 OpenJDK 17u / HotSpot / Linux 源码为准；不同 JDK 版本或不同 OS 的细节可能不同。

不如直接看主链路上的核心源码。

先看 Java 层 `Thread.start()`：

```java
public synchronized void start() {
    if (threadStatus != 0)
        throw new IllegalThreadStateException();

    group.add(this);

    boolean started = false;
    try {
        start0();
        started = true;
    } finally {
        if (!started) {
            group.threadStartFailed(this);
        }
    }
}

private native void start0();
```

这里能直接看出两件事：

- Java 层先用 `threadStatus != 0` 阻止重复启动
- 真正进入 JVM/HotSpot 的入口是 native `start0()`

再看 VM 入口 `JVM_StartThread(...)`：

```cpp
JVM_ENTRY(void, JVM_StartThread(JNIEnv* env, jobject jthread))
  ...
  jlong size =
         java_lang_Thread::stackSize(JNIHandles::resolve_non_null(jthread));
  size_t sz = size > 0 ? (size_t) size : 0;
  native_thread = new JavaThread(&thread_entry, sz);

  if (native_thread->osthread() != NULL) {
    native_thread->prepare(jthread);
  }
  ...
  if (native_thread->osthread() == NULL) {
    THROW_MSG(vmSymbols::java_lang_OutOfMemoryError(),
              os::native_thread_creation_failed_msg());
  }

  Thread::start(native_thread);
JVM_END
```

这段代码把 `start()` 变成了几个非常具体的动作：

- 从 Java `Thread` 对象里取出 `stackSize`
- 创建 HotSpot 的 `JavaThread`
- 如果底层 `OSThread` 创建成功，就执行 `prepare(jthread)`
- 如果底层线程没创建出来，直接抛 `OutOfMemoryError`
- 最后调用 `Thread::start(native_thread)` 真正启动

`JavaThread` 构造和 `prepare()` 分别负责“创建 VM 线程对象”和“把它接到 Java 线程对象/线程列表上”：

```cpp
JavaThread::JavaThread(ThreadFunction entry_point, size_t stack_sz) : JavaThread() {
  _jni_attach_state = _not_attaching_via_jni;
  set_entry_point(entry_point);
  os::create_thread(this, thr_type, stack_sz);
}

void JavaThread::prepare(jobject jni_thread, ThreadPriority prio) {
  Handle thread_oop(Thread::current(), JNIHandles::resolve_non_null(jni_thread));
  set_threadObj(thread_oop());
  ...
  Threads::add(this);
  java_lang_Thread::release_set_thread(thread_oop(), this);
}
```

这里可以直接对应到两个关键点：

- `JavaThread(...)` 构造期间就调用了 `os::create_thread(...)`
- `prepare(...)` 才把 Java 层 `Thread` 对象和 HotSpot 的 `JavaThread*` 绑定起来，并把线程加入 `Threads` 列表

再看 Linux 平台上真正的 OS 线程创建：

```cpp
bool os::create_thread(Thread* thread, ThreadType thr_type, size_t req_stack_size) {
  OSThread* osthread = new OSThread(NULL, NULL);
  ...
  pthread_attr_init(&attr);
  pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);

  size_t stack_size = os::Posix::get_initial_stack_size(thr_type, req_stack_size);
  size_t guard_size = os::Linux::default_guard_size(thr_type);
  pthread_attr_setguardsize(&attr, guard_size);
  pthread_attr_setstacksize(&attr, stack_size);

  ret = pthread_create(&tid, &attr, (void* (*)(void*)) thread_native_entry, thread);
  ...
}
```

这段源码能直接支撑前面“分配线程相关本地资源”的几个说法：

- HotSpot 会先分配一个 `OSThread`
- 会配置 `pthread` 属性
- 会处理线程栈大小和 guard size
- 最终通过 `pthread_create(...)` 创建原生线程

新线程创建出来以后，并不会立刻自由运行；它先进入 `thread_native_entry()`：

```cpp
static void *thread_native_entry(Thread *thread) {
  thread->record_stack_base_and_size();
  thread->initialize_thread_current();
  ...
  osthread->set_state(INITIALIZED);
  sync->notify_all();

  while (osthread->get_state() == INITIALIZED) {
    sync->wait_without_safepoint_check();
  }

  thread->call_run();
}

void os::pd_start_thread(Thread* thread) {
  Monitor* sync_with_child = thread->osthread()->startThread_lock();
  MutexLocker ml(sync_with_child, Mutex::_no_safepoint_check_flag);
  sync_with_child->notify();
}
```

这说明：

- 子线程一启动就先记录自己的栈边界，并设置当前线程 TLS
- 然后进入 `INITIALIZED` 状态等待父线程放行
- 父线程后续通过 `os::pd_start_thread()` 把它从这个等待点唤醒
- 被唤醒后才继续进入 `thread->call_run()`

最后看 HotSpot 怎样把这个线程带到 `Thread.run()`：

```cpp
void Thread::start(Thread* thread) {
  if (thread->is_Java_thread()) {
    java_lang_Thread::set_thread_status(thread->as_Java_thread()->threadObj(),
                                        JavaThreadStatus::RUNNABLE);
  }
  os::start_thread(thread);
}

void JavaThread::run() {
  initialize_tlab();
  _stack_overflow_state.create_stack_guard_pages();
  ...
  set_active_handles(JNIHandleBlock::allocate_block());
  thread_main_inner();
}

void JavaThread::thread_main_inner() {
  ...
  this->entry_point()(this, this);
}

static void thread_entry(JavaThread* thread, TRAPS) {
  ...
  JavaCalls::call_virtual(&result,
                          obj,
                          vmClasses::Thread_klass(),
                          vmSymbols::run_method_name(),
                          vmSymbols::void_method_signature(),
                          THREAD);
}
```

这一段把最后几步钉死了：

- `Thread::start(...)` 会先把 Java 层状态设成 `RUNNABLE`
- `JavaThread::run()` 里先做线程私有初始化，再进入 `thread_main_inner()`
- `thread_main_inner()` 调用 `entry_point`
- 普通 Java 线程的 `entry_point` 就是 `thread_entry(...)`
- `thread_entry(...)` 最终通过 `JavaCalls::call_virtual(... run_method_name ...)` 回到 Java 层执行 `Thread.run()`

对传统 platform thread 而言，HotSpot 通常采用 **1:1 映射**：

- 一个 Java 平台线程
- 对应一个 JVM 内部线程实体
- 再对应一个 OS 原生线程

### 3.2.1 “线程相关的本地资源”在源码里具体是什么

如果只按源码来讲，这不是“申请一整块统一的线程上下文内存”，而是几类资源在不同阶段到位。

#### A. OS 线程与栈资源

- Java 层 `Thread` 对象里本来就有一个 `stackSize` 字段；`Thread.java` 对它的注释是“requested stack size”，并明确写了 VM 可以按自己的方式处理，甚至忽略它。
- `JVM_StartThread(...)` 会读取这个 `stackSize`，把它转成 `size_t sz`，再传给 `new JavaThread(&thread_entry, sz)`。
- 在 Linux 平台实现里，`os::create_thread()` 会先分配一个 `OSThread` 对象，然后初始化 `pthread_attr_t`，设置 `PTHREAD_CREATE_DETACHED`、`pthread_attr_setguardsize(...)`、`pthread_attr_setstacksize(...)`，最后调用 `pthread_create(...)`。
- `OSThread` 本身保存的是 OS 线程侧的信息和状态，源码里能直接看到 `_start_proc`、`_start_parm`、`_state`、`_thread_id` 这些字段。
- 新线程真正跑起来后，Linux 的 `thread_native_entry()` 第一件事就是调用 `record_stack_base_and_size()`；而 `Thread::record_stack_base_and_size()` 会记录 `_stack_base` / `_stack_size`，并在 Java 线程场景下调用 `stack_overflow_state()->initialize(stack_base(), stack_end())`。
- `JavaThread::run()` 随后会调用 `_stack_overflow_state.create_stack_guard_pages()`；Linux 平台对应的 guard page 细节落在 `os::pd_create_stack_guard_pages()` 和 `os::remove_stack_guard_pages()`。

#### B. `Thread` 基类上的线程私有 VM 结构

- 并不是所有资源都等到 `pthread_create()` 之后才出现。`JVM_StartThread(...)` 先 `new JavaThread(...)`，而 `JavaThread` 继承自 `Thread`，所以有一批 HotSpot 自己的线程私有结构是在 `Thread::Thread()` 构造期间就分配或初始化的。
- `Thread::Thread()` 里可以直接看到这些动作：
  - `set_resource_area(new (mtThread) ResourceArea())`
  - `set_handle_area(new (mtThread) HandleArea(NULL))`
  - `set_metadata_handles(new ... GrowableArray<Metadata*>)`
  - `_ParkEvent = ParkEvent::Allocate(this)`
- `thread.hpp` 对这些字段的注释也写得很直接：
  - `ResourceArea* _resource_area`：`Thread local resource area for temporary allocation within the VM`
  - `HandleArea* _handle_area`：`Thread local handle area for allocation of handles within the VM`
  - `ParkEvent* _ParkEvent`：用于 `Object monitors`、`JVMTI raw monitors`、`ObjectSynchronizer`
- `Thread` 里还内嵌了 `ThreadLocalAllocBuffer _tlab`；`threadLocalAllocBuffer.hpp` 直接把它定义成“a descriptor for thread-local storage used by the threads for allocation”。真正的初始化动作在 `JavaThread::run()` 里的 `initialize_tlab()`。
- 还有一类“线程本地结构”不是独立堆对象，而是当前线程的 TLS 入口：`Thread::initialize_thread_current()` 会执行 `ThreadLocalStorage::set_thread(this)`，让 HotSpot 能用 TLS 找到当前 `Thread*`。
- 需要注意的是，`active_handles` 这类结构不是全部在构造期一次性建完的。`JavaThread::run()` 里还能看到 `set_active_handles(JNIHandleBlock::allocate_block())`，说明有些句柄块是在新线程真正开始跑时才补齐。

#### C. `JavaThread` 扩展出来的运行时状态

- `thread.hpp` 里可以直接看到 `JavaThread` 比 `Thread` 多出来的一批核心字段：
  - `_threadObj`：Java 层 `Thread` 对象
  - `_anchor`：`JavaFrameAnchor`
  - `_jni_environment`：`JNIEnv`
  - `_thread_state`、`_poll_data`、`_safepoint_state`
  - `_stack_overflow_state`
  - `_current_pending_monitor`、`_current_waiting_monitor`
- `JavaThread::JavaThread()` 的初始化列表和构造函数体进一步说明了这些线程私有运行时结构是怎样建立的：
  - 初始化 `_thread_state(_thread_new)`、`_jni_attach_state(_not_attaching_via_jni)` 等状态字段
  - 分配 `_SleepEvent(ParkEvent::Allocate(this))`
  - 调用 `ThreadSafepointState::create(this)`
  - 调用 `SafepointMechanism::initialize_header(this)`
- `JavaThread::prepare(jthread)` 说明了 Java `Thread` 对象和 HotSpot 里的 `JavaThread` 并不是从一开始就自动绑死的，而是在这里：
  - `set_threadObj(thread_oop())`
  - `Threads::add(this)`
  - `java_lang_Thread::release_set_thread(thread_oop(), this)`

#### D. 它不等于 Java 层的 `ThreadLocalMap`

- `java.lang.Thread` 里确实也有 `threadLocals` 和 `inheritableThreadLocals` 两个字段，而且 `Thread.java` 注释明确写了它们分别由 `ThreadLocal` 和 `InheritableThreadLocal` 维护。
- 但这两个字段属于 Java 层 `Thread` 对象的普通字段，不是前面说的 `OSThread` / `Thread` / `JavaThread` 这组 HotSpot native 运行时结构。
- `Thread.exit()` 在退出时会把 `threadLocals` 和 `inheritableThreadLocals` 置空，也说明这部分是 Java 对象生命周期上的清理，不是 OS 线程栈或 HotSpot 内部线程控制块本身。

#### 源码依据

- `Thread.start()`、`threadLocals`、`inheritableThreadLocals`、`stackSize`、`exit()`：
  [OpenJDK 17u `Thread.java`](https://github.com/openjdk/jdk17u/blob/master/src/java.base/share/classes/java/lang/Thread.java#L188-L203)
  [OpenJDK 17u `Thread.java`](https://github.com/openjdk/jdk17u/blob/master/src/java.base/share/classes/java/lang/Thread.java#L441-L458)
  [OpenJDK 17u `Thread.java`](https://github.com/openjdk/jdk17u/blob/master/src/java.base/share/classes/java/lang/Thread.java#L791-L823)
  [OpenJDK 17u `Thread.java`](https://github.com/openjdk/jdk17u/blob/master/src/java.base/share/classes/java/lang/Thread.java#L848-L860)
- `JVM_StartThread()`、`thread_entry()`：
  [OpenJDK 17u `jvm.cpp`](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/share/prims/jvm.cpp#L2841-L2935)
- `Thread::Thread()`、`Thread::record_stack_base_and_size()`、`Thread::start()`、`JavaThread::JavaThread()`、`JavaThread::run()`、`JavaThread::prepare()`、`Thread::~Thread()`：
  [OpenJDK 17u `thread.cpp`](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/share/runtime/thread.cpp#L234-L380)
  [OpenJDK 17u `thread.cpp`](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/share/runtime/thread.cpp#L416-L448)
  [OpenJDK 17u `thread.cpp`](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/share/runtime/thread.cpp#L539-L550)
  [OpenJDK 17u `thread.cpp`](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/share/runtime/thread.cpp#L1008-L1105)
  [OpenJDK 17u `thread.cpp`](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/share/runtime/thread.cpp#L1186-L1305)
  [OpenJDK 17u `thread.cpp`](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/share/runtime/thread.cpp#L2200-L2239)
- `Thread` / `JavaThread` 的字段定义：
  [OpenJDK 17u `thread.hpp`](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/share/runtime/thread.hpp#L288-L302)
  [OpenJDK 17u `thread.hpp`](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/share/runtime/thread.hpp#L541-L629)
  [OpenJDK 17u `thread.hpp`](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/share/runtime/thread.hpp#L719-L864)
  [OpenJDK 17u `thread.hpp`](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/share/runtime/thread.hpp#L1018-L1024)
- `OSThread` 的字段定义：
  [OpenJDK 17u `osThread.hpp`](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/share/runtime/osThread.hpp#L36-L98)
- Linux 平台 `pthread_create`、栈大小、guard page、子线程启动握手：
  [OpenJDK 17u `os_linux.cpp`](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/os/linux/os_linux.cpp#L664-L727)
  [OpenJDK 17u `os_linux.cpp`](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/os/linux/os_linux.cpp#L836-L969)
  [OpenJDK 17u `os_linux.cpp`](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/os/linux/os_linux.cpp#L1045-L1050)
  [OpenJDK 17u `os_linux.cpp`](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/os/linux/os_linux.cpp#L3466-L3506)
- `ThreadLocalAllocBuffer` 的定义与注释：
  [OpenJDK 17u `threadLocalAllocBuffer.hpp`](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/share/gc/shared/threadLocalAllocBuffer.hpp#L35-L79)

### 3.3 线程一旦启动，JVM 还要持续管理它

线程启动后，不只是“让它跑起来”这么简单，HotSpot 还要把它纳入整个运行时体系：

- 线程会被加入 JVM 的线程管理结构
- GC 做 root 扫描时，要扫描这个线程栈和寄存器里的对象引用
- 进入 safepoint 时，这个线程也需要配合停下来
- `synchronized`、`park/unpark`、中断、异常处理等，都要和这个线程状态协同

### 3.4 为什么平台线程不是“免费”的

这也是平台线程和普通对象最大的区别之一。每创建一个平台线程，通常都会额外消耗：

- 一段独立的线程栈空间（`-Xss` 会直接影响这里）
- JVM 内部的线程管理结构
- OS 调度与内核线程资源

所以线程太多时，哪怕堆没满，也可能遇到：

```text
java.lang.OutOfMemoryError: unable to create native thread
```

这不是“堆对象分配失败”，而更像是：

- JVM / 进程 / OS 已经无法继续提供新的原生线程资源

### 3.5 线程退出时

当线程执行结束后，HotSpot 还会做善后工作：

- 将线程从内部线程列表中移除
- 通知等待 `join()` 的其他线程
- 释放线程栈和底层 native thread 资源
- Java 层的 `Thread` 对象本身仍然是普通堆对象，最终交给 GC 回收

这里可以把 HotSpot 视角理解成一句话：

- **HotSpot 负责把 Java 里的 `Thread` 对象，落成真正受 OS 调度的执行线程。**

---

## 4. JMM 视角：线程启动和结束时的内存语义

JMM 并不关心 HotSpot 具体调用了哪个 OS API 去造线程，它更关心：

- 线程启动前后的写入能不能被新线程看见
- 线程结束后的结果能不能被其他线程可靠观察到

关键是两条 `happens-before` 规则。

### 4.1 线程启动规则：`start()`

一个线程在调用另一个线程的 `start()` 之前所做的操作，`happens-before` 新线程中的任何操作。

例如：

```java
int x = 42;

Thread t = new Thread(() -> {
    System.out.println(x);
});

t.start();
```

可以这样理解：

- 主线程在 `start()` 之前对 `x` 的写入
- 对新线程启动后的代码来说是可见的

所以从 JMM 角度看，`start()` 不只是“启动线程”，它还像一次**安全发布**。

### 4.2 构造函数里启动线程与 `this` 逃逸

还有一个常见说法：

- **在 Java 构造函数中启动线程，会造成 `this` 指针逃逸，这始终是一个隐患。**

这个说法作为工程经验基本正确，但要精确理解：

- 不是“构造函数里调用 `new Thread().start()` 这个动作本身必然导致 `this` 逃逸”
- 而是“如果新线程能直接或间接拿到当前正在构造的对象引用，就发生了 `this` 逃逸”

典型问题代码：

```java
class Worker {
    private int value;

    Worker() {
        new Thread(() -> {
            System.out.println(value); // lambda 隐式捕获 this
        }).start();

        value = 42;
    }
}
```

这里新线程可能在构造函数还没执行完时就运行。它读到的 `value` 可能还是默认值 `0`，也可能看到一个对象不变量尚未建立完成的中间状态。

更直接的写法是：

```java
class Worker {
    Worker() {
        new Thread(this::run).start(); // 直接把 this 交给新线程
    }

    private void run() {
        // 使用当前对象状态
    }
}
```

这就是 `this` escape：对象还没有构造完成，`this` 引用已经被另一个线程拿到了。

容易混淆的是，`Thread.start()` 本身有 `happens-before` 语义：

```java
class Worker {
    private int value;

    Worker() {
        value = 42;
        new Thread(() -> System.out.println(value)).start();
    }
}
```

因为 `value = 42` 在 `start()` 之前，所以新线程启动后的代码可以看到这个写入。但这不等于“构造函数里启动线程就是好设计”，原因是：

- 构造函数还没有返回，对象还处在构造期
- 对象不变量可能还没完全建立
- 子类初始化可能还没完成
- `final` 字段的初始化安全语义要求构造过程中 `this` 不要提前逃逸
- 后续维护时很容易在 `start()` 后继续添加初始化逻辑，引入竞态

更稳妥的做法是把“构造对象”和“启动线程”拆开：

```java
class Worker {
    private final int value;

    Worker(int value) {
        this.value = value;
    }

    void start() {
        new Thread(this::run).start();
    }

    private void run() {
        System.out.println(value);
    }
}
```

也可以用静态工厂把顺序收束起来：

```java
static Worker createAndStart(int value) {
    Worker worker = new Worker(value);
    worker.start();
    return worker;
}
```

所以结论是：

- 如果构造函数里启动的线程持有当前对象引用，确实是 `this` 逃逸
- 严格说，只有新线程直接或间接持有当前对象引用时，才构成 `this` 逃逸
- 实际代码里这种情况非常常见，所以通常应避免在构造函数中启动线程

### 4.3 线程终止规则：`join()`

一个线程中的所有操作，`happens-before` 其他线程成功从这个线程的 `join()` 返回。

例如：

```java
int[] result = new int[1];

Thread t = new Thread(() -> {
    result[0] = 42;
});

t.start();
t.join();

System.out.println(result[0]);
```

`join()` 返回后，主线程必须能看到 `result[0] = 42` 的结果。

所以从 JMM 角度看：

- `start()` 像“发布起点”
- `join()` 像“结果收口”

### 4.4 JMM 关注的是“可见性边界”，不是“线程怎么造出来”

这也是很多人最容易混在一起的地方：

- **JVM 规范 / HotSpot 实现**：更关心线程上下文怎么建立、怎么调度、怎么清理
- **JMM**：更关心这些线程之间共享数据时，什么可见、什么有序、什么可以被正确推理

所以你看到 `thread.start()` 时，可以同时从两层去想：

- 对 JVM/HotSpot：这是“启动一个线程”
- 对 JMM：这是“建立一条明确的 happens-before 边界”

---

## 5. 把三层视角按顺序串起来

如果把整个过程压成一条时间线，可以记成：

1. Java 执行 `new Thread(...)`，先在堆上创建 `Thread` 对象
2. Java 调用 `thread.start()`，JVM 检查线程状态是否合法
3. JVM 从规范层面为它建立线程私有执行上下文
4. HotSpot 从实现层面分配线程相关本地资源，并创建 OS 原生线程
5. 新线程被调度后开始执行，最终进入 `Thread.run()`
6. 同时，JMM 赋予 `start()` 和 `join()` 明确的可见性与有序性语义
7. 线程结束后，JVM/HotSpot 清理线程资源，其他线程可通过 `join()` 安全收集结果

如果再压缩成一句话：

- **`new Thread()` 是“创建对象”，`start()` 是“启动执行流”，而 JMM 则规定了这个启动和结束过程在内存可见性上的边界。**
