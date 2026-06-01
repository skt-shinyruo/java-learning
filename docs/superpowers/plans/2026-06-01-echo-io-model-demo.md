# Echo I/O Model Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a real loopback echo server/client demo that explains synchronous, asynchronous, blocking, and non-blocking I/O models in the `nio` module.

**Architecture:** `EchoIoModelDemo` exposes four runnable modes and returns a small `EchoResult` containing the echoed response plus teaching-oriented event logs. Blocking, non-blocking, asynchronous-blocking, and asynchronous-non-blocking paths each use real local TCP sockets bound to `localhost:0`, with JUnit 4 tests verifying the event logs and responses. `NioDirectMemoryApp` adds an `echo` command and the concept document links the commands and code paths.

**Tech Stack:** Java 8, Maven, JUnit 4, `java.net`, Java NIO channels/selectors, `AsynchronousSocketChannel`, `CompletionHandler`.

---

## File Structure

- Create `nio/src/main/java/yier/bubu/nio/EchoIoModelDemo.java`: demo API, four echo implementations, event-log result type, and a `main` method.
- Create `nio/src/test/java/yier/bubu/nio/EchoIoModelDemoTest.java`: JUnit 4 coverage for all four modes and command dispatch.
- Modify `nio/src/main/java/yier/bubu/nio/NioDirectMemoryApp.java`: add `echo <mode>` command dispatch and help text.
- Modify `nio/docs/io-sync-async-blocking-nonblocking.md`: add runnable commands and source/test links.

## Task 1: Add Failing Echo Demo Tests

**Files:**
- Create: `nio/src/test/java/yier/bubu/nio/EchoIoModelDemoTest.java`

- [ ] **Step 1: Write the failing test file**

```java
package yier.bubu.nio;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

public class EchoIoModelDemoTest {
    @Test
    public void runSyncBlocking_shouldEchoWithBlockingReadEvent() throws Exception {
        EchoIoModelDemo.EchoResult result = EchoIoModelDemo.runSyncBlocking();

        assertEcho(result, "sync-blocking");
        assertContains(result.events(), "client: blocking read waits for echo");
        assertContains(result.events(), "server: echo ping");
    }

    @Test
    public void runSyncNonBlocking_shouldEchoWithSelectorEvents() throws Exception {
        EchoIoModelDemo.EchoResult result = EchoIoModelDemo.runSyncNonBlocking();

        assertEcho(result, "sync-nonblocking");
        assertContains(result.events(), "client: selector reports connect ready");
        assertContains(result.events(), "client: selector reports read ready");
        assertContains(result.events(), "server: echo ping");
    }

    @Test
    public void runAsyncBlocking_shouldEchoWithFutureGetEvent() throws Exception {
        EchoIoModelDemo.EchoResult result = EchoIoModelDemo.runAsyncBlocking();

        assertEcho(result, "async-blocking");
        assertContains(result.events(), "client: Future.get waits for read");
        assertContains(result.events(), "server: echo ping");
    }

    @Test
    public void runAsyncNonBlocking_shouldEchoWithCompletionHandlerEvent() throws Exception {
        EchoIoModelDemo.EchoResult result = EchoIoModelDemo.runAsyncNonBlocking();

        assertEcho(result, "async-nonblocking");
        assertContains(result.events(), "client: CompletionHandler read ping");
        assertContains(result.events(), "server: echo ping");
    }

    @Test
    public void run_shouldRejectUnknownMode() throws Exception {
        try {
            EchoIoModelDemo.run("unknown");
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            Assert.assertEquals("Unknown echo mode: unknown", expected.getMessage());
        }
    }

    @Test
    public void appEchoCommand_shouldPrintEchoResult() throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, "UTF-8"));

            NioDirectMemoryApp.main(new String[]{"echo", "sync-blocking"});
        } finally {
            System.setOut(originalOut);
        }

        String text = output.toString("UTF-8");
        Assert.assertTrue(text.contains("[EchoIoModelDemo]"));
        Assert.assertTrue(text.contains("mode=sync-blocking"));
        Assert.assertTrue(text.contains("response=ping"));
        Assert.assertTrue(text.contains("client: blocking read waits for echo"));
    }

    private void assertEcho(EchoIoModelDemo.EchoResult result, String mode) {
        Assert.assertEquals(mode, result.mode());
        Assert.assertEquals("ping", result.request());
        Assert.assertEquals("ping", result.response());
    }

    private void assertContains(List<String> events, String expected) {
        Assert.assertTrue("Expected event log to contain: " + expected + ", actual=" + events,
                events.contains(expected));
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
mvn -pl nio -Dtest=EchoIoModelDemoTest test
```

Expected: compilation fails because `EchoIoModelDemo` does not exist.

## Task 2: Implement EchoIoModelDemo

**Files:**
- Create: `nio/src/main/java/yier/bubu/nio/EchoIoModelDemo.java`
- Test: `nio/src/test/java/yier/bubu/nio/EchoIoModelDemoTest.java`

- [ ] **Step 1: Write the implementation**

```java
package yier.bubu.nio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class EchoIoModelDemo {
    private static final String REQUEST = "ping";
    private static final int TIMEOUT_SECONDS = 5;

    private EchoIoModelDemo() {
    }

    public static EchoResult run(String mode) throws Exception {
        if ("sync-blocking".equals(mode)) {
            return runSyncBlocking();
        }
        if ("sync-nonblocking".equals(mode)) {
            return runSyncNonBlocking();
        }
        if ("async-blocking".equals(mode)) {
            return runAsyncBlocking();
        }
        if ("async-nonblocking".equals(mode)) {
            return runAsyncNonBlocking();
        }
        throw new IllegalArgumentException("Unknown echo mode: " + mode);
    }

    public static EchoResult runSyncBlocking() throws Exception {
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final int port = serverSocket.getLocalPort();
            Future<Void> server = executor.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    try (Socket accepted = serverSocket.accept()) {
                        events.add("server: accept");
                        InputStream in = accepted.getInputStream();
                        OutputStream out = accepted.getOutputStream();
                        String request = readAvailableMessage(in);
                        events.add("server: read " + request);
                        out.write(request.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        events.add("server: echo " + request);
                    }
                    return null;
                }
            });

            String response;
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                events.add("client: connect");
                client.getOutputStream().write(REQUEST.getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                client.shutdownOutput();
                events.add("client: write " + REQUEST);
                events.add("client: blocking read waits for echo");
                response = readAvailableMessage(client.getInputStream());
                events.add("client: read " + response);
            }

            waitFor(server);
            return result("sync-blocking", response, events);
        } finally {
            executor.shutdownNow();
        }
    }

    public static EchoResult runSyncNonBlocking() throws IOException {
        List<String> events = new ArrayList<String>();
        ByteBuffer clientWrite = ByteBuffer.wrap(REQUEST.getBytes(StandardCharsets.UTF_8));
        ByteBuffer clientRead = ByteBuffer.allocate(64);
        ByteBuffer serverRead = ByteBuffer.allocate(64);
        String response = null;
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);

        try (Selector selector = Selector.open();
             ServerSocketChannel server = ServerSocketChannel.open();
             SocketChannel client = SocketChannel.open()) {
            server.configureBlocking(false);
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            server.register(selector, SelectionKey.OP_ACCEPT);

            client.configureBlocking(false);
            boolean connected = client.connect(server.getLocalAddress());
            if (connected) {
                client.register(selector, SelectionKey.OP_WRITE);
                events.add("client: selector reports connect ready");
            } else {
                client.register(selector, SelectionKey.OP_CONNECT);
                events.add("client: connect started");
            }

            while (response == null && System.currentTimeMillis() < deadline) {
                int selected = selector.select(200);
                if (selected == 0) {
                    events.add("client: selector timeout, do other work");
                    continue;
                }

                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = keys.iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();

                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
                        SocketChannel accepted = serverChannel.accept();
                        accepted.configureBlocking(false);
                        accepted.register(selector, SelectionKey.OP_READ);
                        events.add("server: accept");
                    } else if (key.isConnectable()) {
                        SocketChannel channel = (SocketChannel) key.channel();
                        if (channel.finishConnect()) {
                            events.add("client: selector reports connect ready");
                            key.interestOps(SelectionKey.OP_WRITE);
                        }
                    } else if (key.isWritable()) {
                        SocketChannel channel = (SocketChannel) key.channel();
                        channel.write(clientWrite);
                        if (!clientWrite.hasRemaining()) {
                            events.add("client: write " + REQUEST);
                            key.interestOps(SelectionKey.OP_READ);
                        }
                    } else if (key.isReadable()) {
                        SocketChannel channel = (SocketChannel) key.channel();
                        if (channel == client) {
                            channel.read(clientRead);
                            clientRead.flip();
                            response = decode(clientRead);
                            events.add("client: selector reports read ready");
                            events.add("client: read " + response);
                            key.cancel();
                        } else {
                            channel.read(serverRead);
                            serverRead.flip();
                            String request = decode(serverRead);
                            events.add("server: read " + request);
                            ByteBuffer responseBuffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));
                            while (responseBuffer.hasRemaining()) {
                                channel.write(responseBuffer);
                            }
                            events.add("server: echo " + request);
                            channel.close();
                        }
                    }
                }
            }
        }

        if (response == null) {
            throw new IOException("Timed out waiting for sync-nonblocking echo");
        }
        return result("sync-nonblocking", response, events);
    }

    public static EchoResult runAsyncBlocking() throws Exception {
        List<String> events = new ArrayList<String>();
        ByteBuffer clientRead = ByteBuffer.allocate(64);
        ByteBuffer serverRead = ByteBuffer.allocate(64);

        try (AsynchronousServerSocketChannel server = AsynchronousServerSocketChannel.open();
             AsynchronousSocketChannel client = AsynchronousSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            Future<AsynchronousSocketChannel> acceptedFuture = server.accept();
            client.connect(server.getLocalAddress()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            events.add("client: Future.get waits for connect");

            try (AsynchronousSocketChannel accepted = acceptedFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                events.add("server: accept");
                client.write(ByteBuffer.wrap(REQUEST.getBytes(StandardCharsets.UTF_8))).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                events.add("client: Future.get waits for write");
                accepted.read(serverRead).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                serverRead.flip();
                String request = decode(serverRead);
                events.add("server: read " + request);
                accepted.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8))).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                events.add("server: echo " + request);
                events.add("client: Future.get waits for read");
                client.read(clientRead).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }

        clientRead.flip();
        String response = decode(clientRead);
        events.add("client: read " + response);
        return result("async-blocking", response, events);
    }

    public static EchoResult runAsyncNonBlocking() throws Exception {
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> response = new AtomicReference<String>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final AtomicReference<AsynchronousSocketChannel> acceptedRef = new AtomicReference<AsynchronousSocketChannel>();
        final AtomicReference<AsynchronousSocketChannel> clientRef = new AtomicReference<AsynchronousSocketChannel>();

        try (final AsynchronousServerSocketChannel server = AsynchronousServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            server.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
                @Override
                public void completed(final AsynchronousSocketChannel accepted, Void attachment) {
                    acceptedRef.set(accepted);
                    events.add("server: accept");
                    final ByteBuffer serverRead = ByteBuffer.allocate(64);
                    accepted.read(serverRead, null, new CompletionHandler<Integer, Void>() {
                        @Override
                        public void completed(Integer read, Void attachment) {
                            try {
                                serverRead.flip();
                                String request = decode(serverRead);
                                events.add("server: read " + request);
                                accepted.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)), null,
                                        new CompletionHandler<Integer, Void>() {
                                            @Override
                                            public void completed(Integer written, Void attachment) {
                                                events.add("server: echo " + request);
                                                closeQuietly(accepted);
                                            }

                                            @Override
                                            public void failed(Throwable exc, Void attachment) {
                                                failure.set(exc);
                                                closeQuietly(accepted);
                                                done.countDown();
                                            }
                                        });
                            } catch (Throwable exc) {
                                failure.set(exc);
                                closeQuietly(accepted);
                                done.countDown();
                            }
                        }

                        @Override
                        public void failed(Throwable exc, Void attachment) {
                            failure.set(exc);
                            closeQuietly(accepted);
                            done.countDown();
                        }
                    });
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    failure.set(exc);
                    done.countDown();
                }
            });

            final AsynchronousSocketChannel client = AsynchronousSocketChannel.open();
            clientRef.set(client);
            client.connect(server.getLocalAddress(), null, new CompletionHandler<Void, Void>() {
                @Override
                public void completed(Void result, Void attachment) {
                    events.add("client: CompletionHandler connect");
                    client.write(ByteBuffer.wrap(REQUEST.getBytes(StandardCharsets.UTF_8)), null,
                            new CompletionHandler<Integer, Void>() {
                                @Override
                                public void completed(Integer written, Void attachment) {
                                    events.add("client: CompletionHandler write " + REQUEST);
                                    final ByteBuffer clientRead = ByteBuffer.allocate(64);
                                    client.read(clientRead, null, new CompletionHandler<Integer, Void>() {
                                        @Override
                                        public void completed(Integer read, Void attachment) {
                                            clientRead.flip();
                                            String text = decode(clientRead);
                                            response.set(text);
                                            events.add("client: CompletionHandler read " + text);
                                            closeQuietly(client);
                                            done.countDown();
                                        }

                                        @Override
                                        public void failed(Throwable exc, Void attachment) {
                                            failure.set(exc);
                                            closeQuietly(client);
                                            done.countDown();
                                        }
                                    });
                                }

                                @Override
                                public void failed(Throwable exc, Void attachment) {
                                    failure.set(exc);
                                    closeQuietly(client);
                                    done.countDown();
                                }
                            });
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    failure.set(exc);
                    closeQuietly(client);
                    done.countDown();
                }
            });

            if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for async-nonblocking echo");
            }
            if (failure.get() != null) {
                throw new IOException("Async echo failed", failure.get());
            }
            return result("async-nonblocking", response.get(), events);
        } finally {
            closeQuietly(clientRef.get());
            closeQuietly(acceptedRef.get());
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "sync-blocking" : args[0];
        print(run(mode));
    }

    public static void print(EchoResult result) {
        System.out.println("[EchoIoModelDemo]");
        System.out.println("mode=" + result.mode());
        System.out.println("request=" + result.request());
        System.out.println("response=" + result.response());
        for (String event : result.events()) {
            System.out.println(event);
        }
    }

    private static EchoResult result(String mode, String response, List<String> events) {
        return new EchoResult(mode, REQUEST, response, copy(events));
    }

    private static List<String> copy(List<String> events) {
        synchronized (events) {
            return new ArrayList<String>(events);
        }
    }

    private static void waitFor(Future<Void> future) throws Exception {
        try {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    private static String readAvailableMessage(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String decode(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void closeQuietly(AsynchronousSocketChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static final class EchoResult {
        private final String mode;
        private final String request;
        private final String response;
        private final List<String> events;

        private EchoResult(String mode, String request, String response, List<String> events) {
            this.mode = mode;
            this.request = request;
            this.response = response;
            this.events = Collections.unmodifiableList(events);
        }

        public String mode() {
            return mode;
        }

        public String request() {
            return request;
        }

        public String response() {
            return response;
        }

        public List<String> events() {
            return events;
        }
    }
}
```

- [ ] **Step 2: Run the focused test and verify GREEN for the demo API**

Run:

```bash
mvn -pl nio -Dtest=EchoIoModelDemoTest test
```

Expected: tests still fail only because `NioDirectMemoryApp` does not yet support the `echo` command.

## Task 3: Wire the Echo Command

**Files:**
- Modify: `nio/src/main/java/yier/bubu/nio/NioDirectMemoryApp.java`
- Test: `nio/src/test/java/yier/bubu/nio/EchoIoModelDemoTest.java`

- [ ] **Step 1: Update command dispatch**

Replace `NioDirectMemoryApp.java` with:

```java
package yier.bubu.nio;

import java.nio.file.Files;
import java.nio.file.Path;

public final class NioDirectMemoryApp {
    private NioDirectMemoryApp() {
    }

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "help" : args[0];
        if ("copy-path".equals(command)) {
            String[] rest = withoutCommand(args);
            DirectBufferCopyPathDemo.main(rest);
            return;
        }
        if ("mmap".equals(command)) {
            runMmapDemo();
            return;
        }
        if ("echo".equals(command)) {
            String[] rest = withoutCommand(args);
            EchoIoModelDemo.main(rest);
            return;
        }
        printHelp();
    }

    private static String[] withoutCommand(String[] args) {
        String[] rest = new String[Math.max(0, args.length - 1)];
        if (rest.length > 0) {
            System.arraycopy(args, 1, rest, 0, rest.length);
        }
        return rest;
    }

    private static void runMmapDemo() throws Exception {
        Path file = Files.createTempFile("nio-direct-memory-app", ".txt");
        try {
            MappedFileDemo.writeText(file, "hello mmap");
            System.out.println("mapped file=" + file);
            System.out.println("content=" + MappedFileDemo.readText(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void printHelp() {
        System.out.println("Usage:");
        System.out.println("  java -cp nio/target/classes yier.bubu.nio.NioDirectMemoryApp <command>");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  copy-path [capacity]  Compare heap and direct ByteBuffer copy paths");
        System.out.println("  mmap                  Write/read a temp file with MappedByteBuffer");
        System.out.println("  echo [mode]           Run echo I/O model demo");
        System.out.println("                         modes: sync-blocking, sync-nonblocking, async-blocking, async-nonblocking");
        System.out.println("  help                  Show this help");
    }
}
```

- [ ] **Step 2: Run the focused test and verify GREEN**

Run:

```bash
mvn -pl nio -Dtest=EchoIoModelDemoTest test
```

Expected: all `EchoIoModelDemoTest` tests pass.

## Task 4: Update Documentation

**Files:**
- Modify: `nio/docs/io-sync-async-blocking-nonblocking.md`

- [ ] **Step 1: Add a runnable examples section**

Append this section before the existing "Java 语境下的一个注意点" section:

````markdown
## 5. 可运行的 echo server/client 示例

`nio` 模块提供了一个完整的本机 echo server/client 示例，用真实 I/O 帮助观察这四种组合。

先编译：

```bash
mvn -pl nio -DskipTests package
```

然后分别运行：

```bash
java -cp nio/target/classes yier.bubu.nio.NioDirectMemoryApp echo sync-blocking
java -cp nio/target/classes yier.bubu.nio.NioDirectMemoryApp echo sync-nonblocking
java -cp nio/target/classes yier.bubu.nio.NioDirectMemoryApp echo async-blocking
java -cp nio/target/classes yier.bubu.nio.NioDirectMemoryApp echo async-nonblocking
```

四个命令都会启动本机 loopback echo server/client，发送 `ping`，再读取同样的 `ping` 响应。区别在于客户端等待结果的方式：

- `sync-blocking`：用 `Socket`，客户端在 `read()` 上等待服务端 echo。
- `sync-nonblocking`：用 `SocketChannel` + `Selector`，客户端通过就绪事件推进连接、写入和读取。
- `async-blocking`：用 `AsynchronousSocketChannel`，但客户端调用 `Future.get()` 等结果。
- `async-nonblocking`：用 `AsynchronousSocketChannel` + `CompletionHandler`，结果通过回调回来。

相关代码：

- `nio/src/main/java/yier/bubu/nio/EchoIoModelDemo.java`
- `nio/src/test/java/yier/bubu/nio/EchoIoModelDemoTest.java`
````

After inserting this section, renumber the original `## 5. Java 语境下的一个注意点` heading to `## 6. Java 语境下的一个注意点`.

- [ ] **Step 2: Run documentation build**

Run:

```bash
mkdocs build -f mkdocs/mkdocs.yml --site-dir /tmp/java-learning-mkdocs-site
```

Expected: documentation builds successfully.

## Task 5: Final Verification

**Files:**
- Verify: `nio/src/main/java/yier/bubu/nio/EchoIoModelDemo.java`
- Verify: `nio/src/main/java/yier/bubu/nio/NioDirectMemoryApp.java`
- Verify: `nio/src/test/java/yier/bubu/nio/EchoIoModelDemoTest.java`
- Verify: `nio/docs/io-sync-async-blocking-nonblocking.md`

- [ ] **Step 1: Run module tests**

Run:

```bash
mvn -pl nio test
```

Expected: all `nio` tests pass.

- [ ] **Step 2: Run a sample command**

Run:

```bash
mvn -pl nio -DskipTests package
java -cp nio/target/classes yier.bubu.nio.NioDirectMemoryApp echo async-nonblocking
```

Expected output contains:

```text
[EchoIoModelDemo]
mode=async-nonblocking
request=ping
response=ping
client: CompletionHandler read ping
```

- [ ] **Step 3: Review git diff**

Run:

```bash
git diff -- nio/src/main/java/yier/bubu/nio/EchoIoModelDemo.java nio/src/main/java/yier/bubu/nio/NioDirectMemoryApp.java nio/src/test/java/yier/bubu/nio/EchoIoModelDemoTest.java nio/docs/io-sync-async-blocking-nonblocking.md
```

Expected: diff only contains the echo demo, command wiring, tests, and documentation updates described in this plan.
