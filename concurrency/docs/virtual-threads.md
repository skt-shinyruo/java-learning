# Virtual Threads

Virtual threads are lightweight threads that reduce the effort of writing, maintaining, and debugging high-throughput concurrent applications.

For background information about virtual threads, see [JEP 444](https://openjdk.java.net/jeps/444).

A thread is the smallest unit of processing that can be scheduled. It runs concurrently with -- and largely independently of -- other such units. It's an instance of [`java.lang.Thread`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.html). There are two kinds of threads, platform threads and virtual threads.

Virtual Threads（虚拟线程）是一种轻量级线程，能够显著降低编写、维护和调试高吞吐并发应用时的复杂度与成本。若要了解这项能力的背景，可以先阅读 [JEP 444](https://openjdk.java.net/jeps/444)。

在 Java 中，thread 是可以被调度的最小处理单元，它会与其他同类单元并发运行，并在很大程度上彼此独立。Thread 本质上是 [`java.lang.Thread`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.html) 的实例，而 Java 里的线程主要分为两类：platform thread 和 virtual thread。

## Topics

- [What is a Platform Thread?](#what-is-a-platform-thread)
- [What is a Virtual Thread?](#what-is-a-virtual-thread)
- [Why Use Virtual Threads?](#why-use-virtual-threads)
- [Creating and Running a Virtual Thread](#creating-and-running-a-virtual-thread)
- [Scheduling Virtual Threads and Pinned Virtual Threads](#scheduling-virtual-threads-and-pinned-virtual-threads)
- [Debugging Virtual Threads](#debugging-virtual-threads)
- [Virtual Threads: An Adoption Guide](#virtual-threads-an-adoption-guide)

下面是对应的中文目录：

- [什么是 Platform Thread？](#what-is-a-platform-thread)
- [什么是 Virtual Thread？](#what-is-a-virtual-thread)
- [为什么要使用 Virtual Threads？](#why-use-virtual-threads)
- [如何创建并运行 Virtual Thread](#creating-and-running-a-virtual-thread)
- [Virtual Thread 的调度与 Pinned Virtual Threads](#scheduling-virtual-threads-and-pinned-virtual-threads)
- [如何调试 Virtual Threads](#debugging-virtual-threads)
- [Virtual Threads：采用指南](#virtual-threads-an-adoption-guide)

## What is a Platform Thread?

A platform thread is implemented as a thin wrapper around an operating system (OS) thread. A platform thread runs Java code on its underlying OS thread, and the platform thread captures its OS thread for the platform thread's entire lifetime. Consequently, the number of available platform threads is limited to the number of OS threads.

Platform threads typically have a large thread stack and other resources that are maintained by the operating system. They are suitable for running all types of tasks but may be a limited resource.

Platform Thread 本质上是对操作系统（OS）线程的一层轻量封装。它会在底层 OS 线程上执行 Java 代码，并在整个生命周期内持续占用该线程，因此系统中可用的 platform thread 数量最终会受到 OS 线程数量的限制。Platform Thread 通常还伴随着较大的线程栈，以及其他由操作系统维护的资源；它适合执行各种类型的任务，但本身也可能成为一种稀缺资源。

## What is a Virtual Thread?

Like a platform thread, a virtual thread is also an instance of `java.lang.Thread`. However, a virtual thread isn't tied to a specific OS thread. A virtual thread still runs code on an OS thread. However, when code running in a virtual thread calls a blocking I/O operation, the Java runtime suspends the virtual thread until it can be resumed. The OS thread associated with the suspended virtual thread is now free to perform operations for other virtual threads.

Virtual threads are implemented in a similar way to virtual memory. To simulate a lot of memory, an operating system maps a large virtual address space to a limited amount of RAM. Similarly, to simulate a lot of threads, the Java runtime maps a large number of virtual threads to a small number of OS threads.

Unlike platform threads, virtual threads typically have a shallow call stack, performing as few as a single HTTP client call or a single JDBC query. Although virtual threads support thread-local variables and inheritable thread-local variables, you should carefully consider using them because a single JVM might support millions of virtual threads.

Virtual threads are suitable for running tasks that spend most of the time blocked, often waiting for I/O operations to complete. However, they aren't intended for long-running CPU-intensive operations.

Virtual Thread 与 platform thread 一样，同样是 `java.lang.Thread` 的实例；但它不会永久绑定到某一个固定的 OS 线程上。它执行代码时依然需要借助 OS 线程，不过当 virtual thread 中的代码发起 blocking I/O 操作时，Java runtime 会先把它挂起，等到可以继续执行时再恢复。这样一来，原先承载它的那个 OS 线程就能被释放出来，用于服务其他 virtual thread。

从实现思路上看，virtual thread 与 virtual memory 很相似：操作系统会把巨大的虚拟地址空间映射到有限的 RAM 上，以此“模拟”出大量可用内存；Java runtime 也会把大量 virtual thread 映射到少量 OS 线程上，以此“模拟”出海量线程。与 platform thread 相比，virtual thread 的调用栈通常更浅，所执行的工作可能只是一条 HTTP client 调用或一次 JDBC query。虽然它仍然支持 thread-local variables 和 inheritable thread-local variables，但在一个 JVM 中可能同时存在数百万个 virtual thread，因此这些机制必须谨慎使用。总体上，virtual thread 适合处理那类大部分时间都在阻塞、经常等待 I/O 完成的任务，而不适合长时间运行的 CPU 密集型工作。

## Why Use Virtual Threads?

Use virtual threads in high-throughput concurrent applications, especially those that consist of a great number of concurrent tasks that spend much of their time waiting. Server applications are examples of high-throughput applications because they typically handle many client requests that perform blocking I/O operations such as fetching resources.

Virtual threads are not faster threads; they do not run code any faster than platform threads. They exist to provide scale (higher throughput), not speed (lower latency).

Virtual Threads 特别适合高吞吐并发应用，尤其适用于由大量并发任务构成、且这些任务大部分时间都在等待的场景。Server application 就是典型例子，因为它们通常要同时处理大量 client request，而这些请求往往包含获取资源之类的 blocking I/O 操作。

需要特别注意的是，virtual thread 并不是“更快的线程”。它不会让代码比 platform thread 执行得更快；它真正提供的是规模扩展能力，也就是更高的 throughput，而不是更低的 latency。

## Creating and Running a Virtual Thread

The `Thread` and `Thread.Builder` APIs provide ways to create both platform and virtual threads. The `java.util.concurrent.Executors` class also defines methods to create an `ExecutorService` that starts a new virtual thread for each task.

`Thread` 与 `Thread.Builder` API 都提供了创建 platform thread 和 virtual thread 的方式。同时，`java.util.concurrent.Executors` 类也定义了一些方法，用来创建一种 `ExecutorService`，它会为每个任务启动一个新的 virtual thread。

### Topics

- [Creating a Virtual Thread with the Thread Class and the Thread.Builder Interface](#creating-a-virtual-thread-with-the-thread-class-and-the-threadbuilder-interface)
- [Creating and Running a Virtual Thread with the Executors.newVirtualThreadPerTaskExecutor() Method](#creating-and-running-a-virtual-thread-with-the-executorsnewvirtualthreadpertaskexecutor-method)
- [Multithreaded Client Server Example](#multithreaded-client-server-example)

下面是本节对应的中文目录：

- [使用 Thread 类与 Thread.Builder 接口创建 Virtual Thread](#creating-a-virtual-thread-with-the-thread-class-and-the-threadbuilder-interface)
- [使用 Executors.newVirtualThreadPerTaskExecutor() 创建并运行 Virtual Thread](#creating-and-running-a-virtual-thread-with-the-executorsnewvirtualthreadpertaskexecutor-method)
- [多线程 Client/Server 示例](#multithreaded-client-server-example)

### Creating a Virtual Thread with the Thread Class and the Thread.Builder Interface

Call the `Thread.ofVirtual()` method to create an instance of `Thread.Builder` for creating virtual threads.

The following example creates and starts a virtual thread that prints a message. It calls the `join` method to wait for the virtual thread to terminate. (This enables you to see the printed message before the main thread terminates.)

要通过 `Thread.Builder` 创建 virtual thread，可以先调用 `Thread.ofVirtual()` 方法拿到一个 `Thread.Builder` 实例。下面这个例子会创建并启动一个 virtual thread，让它输出一条消息；随后再调用 `join` 方法等待该 virtual thread 结束，以确保在 main thread 退出前你能够看到这条输出。

```java
Thread thread = Thread.ofVirtual().start(() -> System.out.println("Hello"));
thread.join();
```

The [`Thread.Builder`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.Builder.html) interface lets you create threads with common `Thread` properties such as the thread's name. The `Thread.Builder.OfPlatform` subinterface creates platform threads while `Thread.Builder.OfVirtual` creates virtual threads.

The following example creates a virtual thread named `MyThread` with the `Thread.Builder` interface:

[`Thread.Builder`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.Builder.html) 接口允许你在创建线程时一并设置常见的 `Thread` 属性，例如线程名称。`Thread.Builder.OfPlatform` 子接口用于创建 platform thread，而 `Thread.Builder.OfVirtual` 则用于创建 virtual thread。下面的例子展示了如何通过 `Thread.Builder` 创建一个名为 `MyThread` 的 virtual thread：

```java
Thread.Builder builder = Thread.ofVirtual().name("MyThread");
Runnable task = () -> {
    System.out.println("Running thread");
};
Thread t = builder.start(task);
System.out.println("Thread t name: " + t.getName());
t.join();
```

The following example creates and starts two virtual threads with `Thread.Builder`:

下面这个例子进一步展示了如何使用 `Thread.Builder` 连续创建并启动两个 virtual thread：

```java
Thread.Builder builder = Thread.ofVirtual().name("worker-", 0);
Runnable task = () -> {
    System.out.println("Thread ID: " + Thread.currentThread().threadId());
};

// name "worker-0"
Thread t1 = builder.start(task);
t1.join();
System.out.println(t1.getName() + " terminated");

// name "worker-1"
Thread t2 = builder.start(task);
t2.join();
System.out.println(t2.getName() + " terminated");
```

This example prints output similar to the following:

该示例输出大致如下：

```text
Thread ID: 21
worker-0 terminated
Thread ID: 24
worker-1 terminated
```

### Creating and Running a Virtual Thread with the Executors.newVirtualThreadPerTaskExecutor() Method

Executors let you to separate thread management and creation from the rest of your application.

The following example creates an `ExecutorService` with the [`Executors.newVirtualThreadPerTaskExecutor()`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executors.html#newVirtualThreadPerTaskExecutor()) method. Whenever `ExecutorService.submit(Runnable)` is called, a new virtual thread is created and started to run the task. This method returns an instance of `Future`. Note that the method `Future.get()` waits for the thread's task to complete. Consequently, this example prints a message once the virtual thread's task is complete.

Executors 的价值在于，它可以把线程的创建与管理逻辑从应用的其他业务逻辑中解耦出来。下面这个例子通过 [`Executors.newVirtualThreadPerTaskExecutor()`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executors.html#newVirtualThreadPerTaskExecutor()) 创建了一个 `ExecutorService`。每当调用 `ExecutorService.submit(Runnable)` 时，都会启动一个新的 virtual thread 去执行对应任务。这个方法会返回 `Future`；而 `Future.get()` 会等待任务执行完成，因此这个示例会在 virtual thread 结束后输出一条消息。

```java
try (ExecutorService myExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
    Future<?> future = myExecutor.submit(() -> System.out.println("Running thread"));
    future.get();
    System.out.println("Task completed");
    // ...
```

### Multithreaded Client Server Example

The following example consists of two classes. `EchoServer` is a server program that listens on a port and starts a new virtual thread for each connection. `EchoClient` is a client program that connects to the server and sends messages entered on the command line.

`EchoClient` creates a socket, thereby getting a connection to `EchoServer`. It reads input from the user on the standard input stream, and then forwards that text to `EchoServer` by writing the text to the socket. `EchoServer` echoes the input back through the socket to the `EchoClient`. `EchoClient` reads and displays the data passed back to it from the server. `EchoServer` can service multiple clients simultaneously through virtual threads, one thread per each client connection.

下面的示例包含两个类。`EchoServer` 是一个 server 程序，它会监听某个端口，并为每个连接启动一个新的 virtual thread；`EchoClient` 则是一个 client 程序，它会连接到这个 server，并发送命令行输入的消息。

`EchoClient` 会先创建 socket，从而建立与 `EchoServer` 的连接。随后，它从标准输入流中读取用户输入，把这些文本写入 socket 并转发给 `EchoServer`；`EchoServer` 再通过 socket 原样回显给 `EchoClient`。`EchoClient` 最终负责读取并展示 server 返回的数据。借助 virtual thread，`EchoServer` 能同时服务多个 client，也就是为每个 client connection 分配一个独立线程。

```java
public class EchoServer {

    public static void main(String[] args) throws IOException {

        if (args.length != 1) {
            System.err.println("Usage: java EchoServer <port>");
            System.exit(1);
        }

        int portNumber = Integer.parseInt(args[0]);
        try (
            ServerSocket serverSocket =
                new ServerSocket(Integer.parseInt(args[0]));
        ) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                // Accept incoming connections
                // Start a service thread
                Thread.ofVirtual().start(() -> {
                    try (
                        PrintWriter out =
                            new PrintWriter(clientSocket.getOutputStream(), true);
                        BufferedReader in = new BufferedReader(
                            new InputStreamReader(clientSocket.getInputStream()));
                    ) {
                        String inputLine;
                        while ((inputLine = in.readLine()) != null) {
                            System.out.println(inputLine);
                            out.println(inputLine);
                        }

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            }
        } catch (IOException e) {
            System.out.println("Exception caught when trying to listen on port "
                + portNumber + " or listening for a connection");
            System.out.println(e.getMessage());
        }
    }
}
```

```java
public class EchoClient {
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println(
                "Usage: java EchoClient <hostname> <port>");
            System.exit(1);
        }
        String hostName = args[0];
        int portNumber = Integer.parseInt(args[1]);
        try (
            Socket echoSocket = new Socket(hostName, portNumber);
            PrintWriter out =
                new PrintWriter(echoSocket.getOutputStream(), true);
            BufferedReader in =
                new BufferedReader(
                    new InputStreamReader(echoSocket.getInputStream()));
        ) {
            BufferedReader stdIn =
                new BufferedReader(
                    new InputStreamReader(System.in));
            String userInput;
            while ((userInput = stdIn.readLine()) != null) {
                out.println(userInput);
                System.out.println("echo: " + in.readLine());
                if (userInput.equals("bye")) break;
            }
        } catch (UnknownHostException e) {
            System.err.println("Don't know about host " + hostName);
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Couldn't get I/O for the connection to " +
                hostName);
            System.exit(1);
        }
    }
}
```

## Scheduling Virtual Threads and Pinned Virtual Threads

The operating system schedules when a platform thread is run. However, the Java runtime schedules when a virtual thread is run. When the Java runtime schedules a virtual thread, it assigns or mounts the virtual thread on a platform thread, then the operating system schedules that platform thread as usual. This platform thread is called a carrier. After running some code, the virtual thread can unmount from its carrier. This usually happens when the virtual thread performs a blocking I/O operation. After a virtual thread unmounts from its carrier, the carrier is free, which means that the Java runtime scheduler can mount a different virtual thread on it.

Virtual Thread 的运行时机由 Java runtime 调度，而 platform thread 的运行时机则由操作系统调度。当 Java runtime 调度某个 virtual thread 时，它会先把该 virtual thread 挂载（mount）到某个 platform thread 上，再由操作系统像往常一样调度这个 platform thread。这个承载 virtual thread 的 platform thread 被称为 carrier。Virtual Thread 运行一段代码之后，还可以再从 carrier 上卸载（unmount）下来；这种情况通常发生在它执行 blocking I/O 的时候。一旦 virtual thread 被卸载，carrier 就重新空闲出来，Java runtime scheduler 就可以把别的 virtual thread 挂载到它上面。

A virtual thread cannot be unmounted during blocking operations when it is pinned to its carrier. A virtual thread is pinned in the following situations:

- The virtual thread runs code inside a `synchronized` block or method
- The virtual thread runs a `native` method or a foreign function (see [Foreign Function and Memory API](https://docs.oracle.com/en/java/javase/21/core/foreign-function-and-memory-api.html#GUID-FBE990DA-C356-46E8-9109-C75567849BA8))

Pinning does not make an application incorrect, but it might hinder its scalability. Try avoiding frequent and long-lived pinning by revising `synchronized` blocks or methods that run frequently and guarding potentially long I/O operations with `java.util.concurrent.locks.ReentrantLock`.

如果某个 virtual thread 在执行阻塞操作时被 pinned 到自己的 carrier 上，它就无法在此期间完成 unmount。典型的 pinning 场景包括：

- virtual thread 在 `synchronized` 代码块或方法内部执行代码
- virtual thread 执行 `native` 方法或 foreign function（参见 [Foreign Function and Memory API](https://docs.oracle.com/en/java/javase/21/core/foreign-function-and-memory-api.html#GUID-FBE990DA-C356-46E8-9109-C75567849BA8)）

Pinning 本身不会让程序逻辑出错，但它会削弱应用的可扩展性。实践中应尽量避免“频繁且持续时间较长”的 pinning，例如重新审视那些高频执行的 `synchronized` 代码块或方法，并考虑用 `java.util.concurrent.locks.ReentrantLock` 来保护可能耗时较长的 I/O 操作。

## Debugging Virtual Threads

Virtual threads are still threads; debuggers can step through them like platform threads. JDK Flight Recorder and the `jcmd` tool have additional features to help you observe virtual threads in your applications.

Virtual Thread 归根结底仍然是线程，因此 debugger 依然可以像处理 platform thread 一样对它进行单步调试。除此之外，JDK Flight Recorder 与 `jcmd` 工具还提供了额外能力，用来帮助你在应用中观测 virtual thread。

### Topics

- [JDK Flight Recorder Events for Virtual Threads](#jdk-flight-recorder-events-for-virtual-threads)
- [Viewing Virtual Threads in jcmd Thread Dumps](#viewing-virtual-threads-in-jcmd-thread-dumps)

下面是本节对应的中文目录：

- [JDK Flight Recorder 中与 Virtual Threads 相关的事件](#jdk-flight-recorder-events-for-virtual-threads)
- [在 jcmd Thread Dump 中查看 Virtual Threads](#viewing-virtual-threads-in-jcmd-thread-dumps)

### JDK Flight Recorder Events for Virtual Threads

JDK Flight Recorder (JFR) can emit these events related to virtual threads:

- `jdk.VirtualThreadStart` and `jdk.VirtualThreadEnd` indicate when a virtual thread starts and ends. These events are disabled by default.
- `jdk.VirtualThreadPinned` indicates that a virtual thread was pinned (and its carrier thread wasn't freed) for longer than the threshold duration. This event is enabled by default with a threshold of 20 ms.
- `jdk.VirtualThreadSubmitFailed` indicates that starting or unparking a virtual thread failed, probably due to a resource issue. Parking a virtual thread releases the underlying carrier thread to do other work, and unparking a virtual thread schedules it to continue. This event is enabled by default.

JDK Flight Recorder（JFR）可以发出多种与 virtual thread 相关的事件，其中包括：

- `jdk.VirtualThreadStart` 与 `jdk.VirtualThreadEnd`：分别表示 virtual thread 的启动与结束，这两个事件默认关闭
- `jdk.VirtualThreadPinned`：表示某个 virtual thread 被 pinned 的时间超过阈值，也就是其 carrier thread 在这段时间内没有被释放；该事件默认开启，阈值为 20 ms
- `jdk.VirtualThreadSubmitFailed`：表示启动 virtual thread 或对其执行 unpark 失败，通常意味着资源不足。所谓 park，是让 virtual thread 挂起并释放底层 carrier thread 去处理其他工作；而 unpark 则会把 virtual thread 重新调度回来继续执行。这个事件默认开启

Enable the events `jdk.VirtualThreadStart` and `jdk.VirtualThreadEnd` through JDK Mission Control or with a custom JFR configuration as described in [Flight Recorder Configurations](https://docs.oracle.com/en/java/javase/21/core/flight-recorder-configurations.html) in _Java Platform, Standard Edition Flight Recorder API Programmer's Guide_.

To print these events, run the following command, where `recording.jfr` is the file name of your recording:

如果你想启用 `jdk.VirtualThreadStart` 与 `jdk.VirtualThreadEnd`，可以通过 JDK Mission Control 来配置，也可以按照 _Java Platform, Standard Edition Flight Recorder API Programmer's Guide_ 中 [Flight Recorder Configurations](https://docs.oracle.com/en/java/javase/21/core/flight-recorder-configurations.html) 的说明，自定义 JFR 配置。若要打印这些事件，则可以执行下面的命令，其中 `recording.jfr` 是录制文件的名称：

```bash
jfr print --events jdk.VirtualThreadStart,jdk.VirtualThreadEnd,jdk.VirtualThreadPinned,jdk.VirtualThreadSubmitFailed recording.jfr
```

### Viewing Virtual Threads in jcmd Thread Dumps

You can create a thread dump in plain text as well as JSON format:

你既可以生成纯文本格式的 thread dump，也可以生成 JSON 格式的 thread dump：

```bash
jcmd <PID> Thread.dump_to_file -format=text <file>
jcmd <PID> Thread.dump_to_file -format=json <file>
```

The `jcmd` thread dump lists all threads, including both platform and virtual threads. However, it doesn't include object addresses, locks, JNI statistics, heap statistics, and other information that appears in traditional thread dumps.

`jcmd` 输出的 thread dump 会列出所有线程，其中既包括 platform thread，也包括 virtual thread。不过，它不会包含传统 thread dump 中常见的对象地址、锁信息、JNI 统计、堆统计等附加内容。

## Virtual Threads: An Adoption Guide

Virtual threads are Java threads that are implemented by the Java runtime rather than the OS. The main difference between virtual threads and the traditional threads -- which we've come to call platform threads -- is that we can easily have a great many active virtual threads, even millions, running in the same Java process. It is their high number that gives virtual threads their power: they can run server applications written in the thread-per-request style more efficiently by allowing the server to process many more requests concurrently, leading to higher throughput and less waste of hardware.

Because virtual threads are an implementation of `java.lang.Thread` and conform to the same rules that specified `java.lang.Thread` since Java SE 1.0, developers don't need to learn new concepts to use them. However, the inability to spawn very many platform threads -- the only implementation of threads available in Java for many years -- has bred practices designed to cope with their high cost. These practices are counterproductive when applied to virtual threads, and must be unlearned. Moreover, the vast difference in cost informs a new way of thinking about threads that may be foreign at first.

This guide is not intended to be comprehensive and cover every important detail of virtual threads. It is meant but to provide an introductory set of guidelines to help those who wish to start using virtual threads make the best of them.

Virtual Thread 是由 Java runtime 而不是 OS 实现的 Java 线程。它与传统线程，也就是现在通常所说的 platform thread，最大的区别在于：在同一个 Java 进程中，我们可以轻松运行数量极多的 active virtual thread，甚至达到数百万个。正是这种“数量优势”赋予了 virtual thread 真正的力量，使它能够让采用 thread-per-request 风格编写的 server application 更高效地处理海量并发请求，从而带来更高的 throughput，并减少硬件资源浪费。

由于 virtual thread 本质上仍然是 `java.lang.Thread` 的一种实现，并且遵循自 Java SE 1.0 以来 `java.lang.Thread` 一直遵守的那套规则，因此开发者并不需要额外学习一整套全新的线程概念。不过，过去很多年里 Java 只有 platform thread 这一种线程实现，而 platform thread 又无法大规模创建，因此围绕“如何应对线程高成本”逐渐形成了大量工程实践。这些做法一旦原样照搬到 virtual thread 上，往往反而会起反效果，因此需要有意识地“反学习”。这份采用指南并不打算穷尽 Virtual Threads 的所有细节，而是希望提供一组入门级实践建议，帮助准备开始使用它的人更好地发挥它们的价值。

### Write Simple, Synchronous Code Employing Blocking I/O APIs in the Thread-Per-Request Style

Virtual threads can significantly improve the throughput -- _not_ the latency -- of servers written in the thread-per-request style. In this style, the server dedicates a thread to processing each incoming request for its entire duration. It dedicates _at least_ one thread because, when processing a single request, you may want to employ more threads to carry some tasks concurrently.

Blocking a platform thread is expensive because it holds on to the thread -- a relatively scarce resource -- while it is not doing much meaningful work. Because virtual threads can be plentiful, blocking them is cheap and encouraged. Therefore, you should write code in the straightforward synchronous style and use blocking I/O APIs.

对于采用 thread-per-request 风格编写的 server 来说，virtual thread 能显著提升的是 throughput，而 _不是_ latency。在这种风格下，server 会为每个传入请求分配一个线程，并让它在请求处理的整个周期内持续服务该请求。之所以说是 _至少_ 一个线程，是因为在处理单个请求时，你仍然可能为了并发执行某些子任务而额外启用更多线程。

Platform Thread 一旦被阻塞，代价通常很高，因为它会在几乎没有做实质性工作的情况下，持续占用这个相对稀缺的线程资源。相比之下，virtual thread 的数量可以非常多，因此阻塞 virtual thread 的成本很低，甚至是被鼓励的。所以，在这种模型下，更适合采用直接、同步的编码方式，并优先使用 blocking I/O API。

For example, the following code, written in the non-blocking, asynchronous style, won't benefit much from virtual threads.

例如，下面这段采用 non-blocking、asynchronous 风格编写的代码，就不会从 virtual thread 中获得太多收益：

```java
CompletableFuture.supplyAsync(info::getUrl, pool)
   .thenCompose(url -> getBodyAsync(url, HttpResponse.BodyHandlers.ofString()))
   .thenApply(info::findImage)
   .thenCompose(url -> getBodyAsync(url, HttpResponse.BodyHandlers.ofByteArray()))
   .thenApply(info::setImageData)
   .thenAccept(this::process)
   .exceptionally(t -> { t.printStackTrace(); return null; });
```

On the other hand, the following code, written in the synchronous style and using simple blocking IO, will benefit greatly:

相反，下面这段代码采用同步风格，并直接使用简单的 blocking IO，因此会从 virtual thread 中获得明显收益：

```java
try {
   String page = getBody(info.getUrl(), HttpResponse.BodyHandlers.ofString());
   String imageUrl = info.findImage(page);
   byte[] data = getBody(imageUrl, HttpResponse.BodyHandlers.ofByteArray());
   info.setImageData(data);
   process(info);
} catch (Exception ex) {
   t.printStackTrace();
}
```

Such code is also easier to debug in a debugger, profile in a profiler, or observe with thread-dumps. To observe virtual threads, create a thread dump with the [`jcmd`](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html) command:

这类代码在 debugger 中更容易调试，在 profiler 中也更容易分析，同时更适合通过 thread dump 进行观测。如果你想直接观察 virtual thread，可以使用 [`jcmd`](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html) 命令生成 thread dump：

```bash
jcmd <pid> Thread.dump_to_file -format=json <file>
```

The more of the stack that's written in this style, the better virtual threads will be for both performance and observability. Programs or frameworks written in other styles that don't dedicate a thread per task should not expect to see a significant benefit from virtual threads. Avoid mixing synchronous, blocking code with asynchronous frameworks.

代码栈中采用这种风格的部分越多，virtual thread 在性能与可观测性两方面带来的收益就越明显。那些采用其他编程风格、并不会为每个任务分配独立线程的程序或框架，不应期待 virtual thread 能带来显著收益。实践中也应避免把同步的 blocking 代码与 asynchronous framework 混在一起使用。

### Represent Every Concurrent Task as a Virtual Thread; Never Pool Virtual Threads

The hardest thing to internalize about virtual threads is that, while they have the same behavior as platform threads they should not represent the same program concept.

Platform threads are scarce, and are therefore a precious _resource_. Precious resources need to be managed, and the most common way to manage platform threads is with thread pools. A question that you then need to answer is, how many threads should we have in the pool?

But virtual threads are plentiful, and so each should represent not some shared, pooled, resource but a task. From a managed resource threads turn into _application domain objects_. The question of how many virtual threads we should have becomes obvious, just as the question of how many strings we should use to store a set of user names in memory is obvious: The number of virtual threads is always equal to the number of concurrent tasks in your application.

Converting _n_ platform threads to _n_ virtual threads would yield little benefit; rather, it's _tasks_ that need to be converted.

关于 virtual thread，最难真正建立起来的认知是：虽然它在行为上与 platform thread 相同，但它不应该再代表同一种程序概念。Platform Thread 是稀缺的，因此首先是一种宝贵的 _resource_；而宝贵资源需要被管理，最常见的管理方式就是 thread pool，这也自然带来了“线程池里到底该有多少线程”这样的问题。

但 virtual thread 并不稀缺，因此每个 virtual thread 代表的，不应再是某种共享、可池化的 resource，而应该是一个 task。换句话说，线程在这里不再主要是“被管理的资源”，而更像是 _application domain objects_。于是，“我们到底需要多少个 virtual thread”这个问题就会变得像“保存一组用户名到底需要多少个字符串”一样显而易见：应用里有多少个并发任务，就应该有多少个 virtual thread。仅仅把 _n_ 个 platform thread 替换成 _n_ 个 virtual thread，收益通常不会太大；真正需要重新建模和转换的，其实是 _tasks_。

To represent every application task as a thread, don't use a shared thread pool executor like in the following example:

为了把每个应用任务都表示为一个线程，不要像下面这样继续使用共享的 thread pool executor：

```java
Future<ResultA> f1 = sharedThreadPoolExecutor.submit(task1);
Future<ResultB> f2 = sharedThreadPoolExecutor.submit(task2);
// ... use futures
```

Instead, use a virtual thread executor like in the following example:

相反，你应当像下面这样使用 virtual thread executor：

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
   Future<ResultA> f1 = executor.submit(task1);
   Future<ResultB> f2 = executor.submit(task2);
   // ... use futures
}
```

The code still uses an `ExecutorService`, but the one returned from `Executors.newVirtualThreadPerTaskExecutor()` doesn't employ a thread pool. Rather, it creates a new virtual thread for each submitted tasks.

Furthermore, that `ExecutorService` itself is lightweight, and we can create a new one just as we would with any simple object. That allows us to rely on the newly added `ExecutorService.close()` method and the try-with-resources construct. The `close` method, that is implicitly called at the end of the try block will automatically wait for all tasks submitted to the `ExecutorService` -- that is, all virtual threads spawned by the `ExecutorService` -- to terminate.

这段代码仍然使用的是 `ExecutorService`，但 `Executors.newVirtualThreadPerTaskExecutor()` 返回的那个 `ExecutorService` 并不会维护 thread pool。它的工作方式是：每提交一个任务，就为该任务创建一个新的 virtual thread。

另外，这种 `ExecutorService` 自身也非常轻量，我们完全可以像创建普通对象一样按需新建它。这使得我们能够自然地依赖新增的 `ExecutorService.close()` 方法，以及 try-with-resources 语法。try 代码块结束时会隐式调用 `close`，而 `close` 会自动等待所有提交给该 `ExecutorService` 的任务全部结束，也就是等待它创建出来的所有 virtual thread 都执行完毕。

This is a particularly useful pattern for fanout scenarios, where you wish to concurrently perform multiple outgoing calls to different services like in the following example:

这种模式在 fanout 场景中特别有用，也就是你希望并发地向多个不同服务发起外部调用，例如下面这个例子：

```java
void handle(Request request, Response response) {
    var url1 = ...
    var url2 = ...

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var future1 = executor.submit(() -> fetchURL(url1));
        var future2 = executor.submit(() -> fetchURL(url2));
        response.send(future1.get() + future2.get());
    } catch (ExecutionException | InterruptedException e) {
        response.fail(e);
    }
}

String fetchURL(URL url) throws IOException {
    try (var in = url.openStream()) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
}
```

You should create a new virtual thread, as shown above, for even small, short-lived concurrent tasks.

For even more help writing the fanout pattern and other common concurrency patterns, with better observability, use structured concurrency.

As a rule of thumb, if your application never has 10,000 virtual threads or more, it is unlikely to benefit from virtual threads. Either it experiences too light a load to need better throughput, or you have not represented sufficiently many tasks to virtual threads.

正如上面的示例所展示的那样，即便面对体量很小、生命周期很短的并发任务，也应当为它们创建独立的 virtual thread。如果你想在编写 fanout 模式以及其他常见并发模式时获得更好的结构性与可观测性，还可以进一步使用 structured concurrency。

一个经验性的判断标准是：如果你的应用从来不会同时拥有 10,000 个以上的 virtual thread，那么它大概率并不会真正从 virtual thread 中获益。这通常意味着，要么应用负载本身并不大，并不需要更高的 throughput；要么就是你还没有把足够多的任务建模成 virtual thread。

### Use Semaphores to Limit Concurrency

Sometimes there is a need to limit the concurrency of a certain operation. For example, some external service may not be able to handle more than ten concurrent requests. Because platform threads are a precious resource that is usually managed in a pool, thread pools have become so ubiquitious that they're used for this purpose of restricting concurrency, like in the following example:

有时确实需要限制某一类操作的并发度。举例来说，某个外部服务也许最多只能同时处理十个并发请求。由于 platform thread 是一种通常需要放进池里统一管理的宝贵资源，thread pool 在工程里已经普及到几乎无处不在，因此很多人也会顺手拿它来做并发限制，就像下面这个例子一样：

```java
ExecutorService es = Executors.newFixedThreadPool(10);
...
Result foo() {
    try {
        var fut = es.submit(() -> callLimitedService());
        return f.get();
    } catch (...) { ... }
}
```

This example ensures that there are at most ten concurrent requests to the limited service.

But restricting concurrency is only a side-effect of thread pools' operation. Pools are designed to share scarce resources, and virtual threads aren't scarce and therefore should never be pooled!

When using virtual threads, if you want to limit the concurrency of accessing some service, you should use a construct designed specifically for that purpose: the [`Semaphore`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Semaphore.html) class. The following example demonstrates this class:

上面的代码能够保证：对受限服务的并发请求数量最多只有十个。但要注意，限制并发其实只是 thread pool 运作过程中的“副作用”。Pool 的本职工作是共享稀缺资源，而 virtual thread 并不是稀缺资源，因此它们不应该被拿来做池化。

在使用 virtual thread 时，如果你希望限制某个服务的访问并发度，就应该使用专门为此设计的并发原语，也就是 [`Semaphore`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Semaphore.html) 类。下面的示例展示了它的基本用法：

```java
Semaphore sem = new Semaphore(10);
...
Result foo() {
    sem.acquire();
    try {
        return callLimitedService();
    } finally {
        sem.release();
    }
}
```

Threads that happen to call `foo` will be throttled, that is, blocked, so that only ten of them can make progress at a time, while others will go about their business unencumbered.

Simply blocking some virtual threads with a semaphore may appear to be substantially different from submitting tasks to a fixed thread pool, but it isn't. Submitting tasks to a thread pool queues them up for later execution, but the semaphore internally (or any other blocking synchronization construct for that matter) creates a queue of threads that are blocked on it that mirrors the queue of tasks waiting for a pooled thread to execute them. Because virtual threads _are_ tasks, the resulting structure is equivalent:

调用 `foo` 的线程会被节流，也就是在必要时被阻塞，从而保证任意时刻最多只有十个线程能够继续前进，而其余线程只是等待，并不会破坏整体并发模型。

表面上看，用 semaphore 去阻塞一部分 virtual thread，似乎和把任务提交给 fixed thread pool 不一样；但从底层结构上说，两者其实非常接近。把任务提交给线程池，本质上是让任务进入队列，等待某个池中线程稍后执行；而 semaphore 在内部，或者说任何阻塞式同步原语在内部，也会形成一个等待队列，只是这里排队的不再是尚未执行的任务，而是已经存在、但被阻塞住的线程。由于 virtual thread 本身就代表 task，因此这两种结构在本质上是等价的，如下图所示：

![Figure 14-1 Comparing a Thread Pool with a Semaphore](https://docs.oracle.com/en/java/javase/21/core/img/java-core-libraries-virtual-threads-thread-pool-and-semaphore.png)

[Description of "Figure 14-1 Comparing a Thread Pool with a Semaphore"](https://docs.oracle.com/en/java/javase/21/core/img_text/java-core-libraries-virtual-threads-thread-pool-and-semaphore.html)

Even though you can think of a pool of platform threads as workers processing tasks that they pull from a queue and of virtual threads as the tasks themselves, blocked until they may continue, the underlying representation in the computer is virtually identical. Recognizing the equivalence between queued tasks and blocked threads will help you make the most of virtual threads.

Database connection pools themselves serve as a semaphore. A connection pool limited to ten connections would block the eleventh thread attempting to acquire a connection. There is no need to add an additional semaphore on top of the connection pool.

你当然可以把 platform thread pool 想象成一组 worker，它们不断从队列里取出 task 来执行；而把 virtual thread 想象成 task 本身，只是这些 task 会在必要时被阻塞，直到可以继续前进为止。但无论采用哪种心智模型，它们在计算机中的底层表示其实几乎是一致的。真正理解“排队中的任务”和“被阻塞的线程”之间的等价关系，将有助于你更充分地用好 virtual thread。

Database connection pool 本身就可以视作一种 semaphore。比如，一个最多只提供十个连接的 connection pool，会自然阻塞第十一个尝试获取连接的线程，因此没有必要再在 connection pool 外面额外套一层 semaphore。

### Don't Cache Expensive Reusable Objects in Thread-Local Variables

Virtual threads support thread-local variables just as platform threads do. See [Thread-Local Variables](https://docs.oracle.com/en/java/javase/21/core/thread-local-variables.html#GUID-2CEB9041-3DF7-43DA-868F-E0596F4B63FD) for more information. Usually, thread-local variables are used to associate some context-specific information with the currently running code, such as the current transaction and user ID. This use of thread-local variables is perfectly reasonable with virtual threads. However, consider using the safer and more efficient scoped values. See [Scoped Values](https://docs.oracle.com/en/java/javase/21/core/scoped-values.html#GUID-9A4565C5-82AE-4F03-A476-3EAA9CDEB0F6) for more information.

There is another use of thread-local variables which is fundamentally at odds with virtual threads: caching reusable objects. These objects are typically expensive to create (and consume a significant amount of memory), are mutable, and not thread-safe. They are cached in a thread-local variable to reduce the number of times they are instantiated and their number of instances in memory, but they are reused by the multiple tasks that run on the thread at different times.

Virtual Thread 和 platform thread 一样，都支持 thread-local variables。更多背景可以参阅 [Thread-Local Variables](https://docs.oracle.com/en/java/javase/21/core/thread-local-variables.html#GUID-2CEB9041-3DF7-43DA-868F-E0596F4B63FD)。通常来说，thread-local variables 会被用来承载与当前执行上下文相关的信息，例如当前 transaction、当前 user ID 等；对于 virtual thread，这种用法是完全合理的。不过，也可以考虑使用更安全、更高效的 scoped values，相关内容可参阅 [Scoped Values](https://docs.oracle.com/en/java/javase/21/core/scoped-values.html#GUID-9A4565C5-82AE-4F03-A476-3EAA9CDEB0F6)。

但 thread-local variables 还有另一种常见用途，而这种用途与 virtual thread 在理念上是根本冲突的，那就是缓存可复用对象。这类对象往往创建成本高、内存占用大、可变，并且不是 thread-safe。之所以把它们缓存到 thread-local variable 中，是为了减少实例化次数、降低内存中同时存在的实例数量，并让多个先后运行在同一个线程上的任务复用这些对象。

For example, an instance of [`SimpleDateFormat`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/text/SimpleDateFormat.html) is expensive to create and isn't thread-safe. A pattern that emerged is to cache such an instance in a [`ThreadLocal`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ThreadLocal.html) like in the following example:

例如，[`SimpleDateFormat`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/text/SimpleDateFormat.html) 的实例创建成本较高，而且并不是 thread-safe 的。因此工程里逐渐形成了一种习惯：把这样的对象缓存在 [`ThreadLocal`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ThreadLocal.html) 中，就像下面这样：

```java
static final ThreadLocal<SimpleDateFormat> cachedFormatter =
       ThreadLocal.withInitial(SimpleDateFormat::new);

void foo() {
  ...
  cachedFormatter.get().format(...);
  ...
}
```

This kind of caching is helpful only when the thread -- and therefore the expensive object cached in the thread local -- is shared and reused by multiple tasks, as would be the case when platform threads are pooled. Many tasks may call `foo` when running in the thread pool, but because the pool only contains a few threads, the object will only be instantiated a few times -- once per pool thread -- cached, and reused.

However, virtual threads are never pooled and never reused by unrelated tasks. Because every task has its own virtual threads, every call to `foo` from a different task would trigger the instantiation of a new `SimpleDateFormat`. Moreover, because there may be a great many virtual threads running concurrently, the expensive object may consume quite a lot of memory. These outcomes are the very opposite of what caching in thread locals intends to achieve.

这种缓存方式只有在“线程本身会被多个任务共享和复用”的前提下才真正有效，也就是说，只有当 thread，以及被缓存在线程本地变量中的昂贵对象，能够被多个任务轮流复用时，它才值得存在。而这正是 platform thread 被放入 thread pool 时的典型情况：许多任务都会调用 `foo`，但由于池中的线程数量有限，对象只需要初始化少数几次，也就是每个池线程一次，然后就可以长期缓存并不断复用。

但 virtual thread 从来不会被池化，也不会被无关任务反复复用。由于每个任务都拥有自己的 virtual thread，不同任务每调用一次 `foo`，都可能导致一个新的 `SimpleDateFormat` 被创建出来。更进一步地说，当系统中同时存在大量 virtual thread 时，这些昂贵对象还会占用大量内存。这与把对象缓存在 thread-local 中所希望达到的效果，恰恰完全相反。

There is no single general alternative to offer, but in the case of `SimpleDateFormat`, you should replace it with [`DateTimeFormatter`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/format/DateTimeFormatter.html). `DateTimeFormatter` is immutable, and so a single instance can be shared by all threads:

对于这类问题并不存在一种通用的万能替代方案，但在 `SimpleDateFormat` 这个例子里，更合适的做法是换成 [`DateTimeFormatter`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/format/DateTimeFormatter.html)。`DateTimeFormatter` 是 immutable 的，因此可以用单个实例在所有线程之间安全共享：

```java
static final DateTimeFormatter formatter = DateTimeFormatter...;

void foo() {
  ...
  formatter.format(...);
  ...
}
```

Note that using thread-local variables to cache shared expensive objects is sometimes done behind the scenes by asynchronous frameworks, under their implicit assumption that they are used by a very small number of pooled threads. This is one reason why mixing virtual threads and asynchronous frameworks is not a good idea: a call to a method may result in instantiating costly objects in thread-local variables that were intended to be cached and shared.

还需要注意的是，一些 asynchronous framework 会在底层悄悄使用 thread-local variables 来缓存这类昂贵且可共享的对象，因为它们默认假设自己只会运行在少量可复用的池线程之上。这也是为什么把 virtual thread 与 asynchronous framework 混用往往不是一个好主意：一次看似普通的方法调用，背后就可能触发 thread-local 中昂贵对象的大量实例化，而这些对象原本本应被缓存和共享。

### Avoid Lengthy and Frequent Pinning

A current limitation of the implementation of virtual threads is that performing a blocking operation while inside a `synchronized` block or method causes the JDK's virtual thread scheduler to block a precious OS thread, whereas it wouldn't if the blocking operation were done outside of a `synchronized` block or method. We call that situation "pinning". Pinning may adversely affect the throughput of the server if the blocking operation is both long-lived and frequent. Guarding short-lived operations, such as in-memory operations, or infrequent ones with `synchronized` blocks or methods should have no adverse effect.

Virtual Thread 当前实现上的一个限制在于：如果某个 blocking 操作发生在 `synchronized` 代码块或方法内部，那么 JDK 的 virtual thread scheduler 就会被迫把一个宝贵的 OS 线程阻塞住；而如果同样的 blocking 操作发生在 `synchronized` 之外，则不会出现这种情况。我们把这种现象称为 “pinning”。如果某个 blocking 操作既持续时间长、又发生得很频繁，那么 pinning 就可能对 server 的 throughput 产生明显负面影响。相反，如果 `synchronized` 只保护一些生命周期很短的操作，例如纯内存操作，或者本身发生频率很低，那么通常不会产生明显问题。

To detect the instances of pinning that might be harmful, (JDK Flight Recorder (JFR) emits the `jdk.VirtualThreadPinned` thread when a blocking operation is pinned; by default this event is enabled when the operation takes longer than 20ms.

Alternatively, you can use the the system property `jdk.tracePinnedThreads` to emit a stack trace when a thread blocks while pinned. Running with the option `-Djdk.tracePinnedThreads=full` prints a complete stack trace when a thread blocks while pinned, highlighting native frames and frames holding monitors. Running with the option `-Djdk.tracePinnedThreads=short` limits the output to just the problematic frames.

为了识别那些可能真正有害的 pinning，JDK Flight Recorder（JFR）会在 blocking 操作发生 pinning 时发出 `jdk.VirtualThreadPinned` 事件；默认情况下，当一次 pinning 持续超过 20ms 时，这个事件就会被记录下来。

另一种办法是使用系统属性 `jdk.tracePinnedThreads`，让 JVM 在某个线程因为 pinning 而阻塞时直接打印 stack trace。使用 `-Djdk.tracePinnedThreads=full` 时，会输出完整堆栈，并高亮其中的 native frame 以及持有 monitor 的 frame；使用 `-Djdk.tracePinnedThreads=short` 时，则只输出那些真正有问题的关键 frame。

If these mechanisms detect places where pinning is both long-lived and frequent, replace the use of `synchronized` with [`ReentrantLock`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html) in those particular places (again, there is no need to replace `synchronized` where it guards a short lived or infrequent operations). The following is an example of long-lived and frequent use of a `syncrhonized` block.

如果这些机制检测到某些位置的 pinning 既持续时间长、又发生频繁，那么就应当在那些特定位置把 `synchronized` 替换为 [`ReentrantLock`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html)。（再次强调，如果 `synchronized` 只是保护很短暂或很少发生的操作，就没有必要为了统一风格而全面替换。）下面就是一个“持续时间长且发生频繁”的 `syncrhonized` 代码块示例：

```java
synchronized(lockObj) {
    frequentIO();
}
```

You can replace it with the following:

你可以把它改写成下面这种形式：

```java
lock.lock();
try {
    frequentIO();
} finally {
    lock.unlock();
}
```
