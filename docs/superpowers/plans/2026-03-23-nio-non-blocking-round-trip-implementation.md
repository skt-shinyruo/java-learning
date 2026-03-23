# NIO Non-Blocking Echo Client/Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single-class non-blocking NIO demo with a dedicated `yier.bubu.jdk.nio.nonblocking` package that contains separate `NonBlockingEchoServer` and `NonBlockingEchoClient` classes, with end-to-end orchestration owned by JUnit tests.

**Architecture:** Introduce one server class and one client class under `jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/`, each keeping its own private non-blocking polling helpers such as `readLine`, `writeFully`, and timeout/backoff logic. Move the round-trip wiring into a single JUnit 4 test class that starts the server on a background thread, waits for published port information, drives the client exchange, captures server-side failures with `AtomicReference<Throwable>`, and then deletes the old `NonBlockingSocketRoundTripDemo` wrapper and its test.

**Tech Stack:** Java 8, Maven, JUnit 4, `ServerSocketChannel`, `SocketChannel`, `ByteBuffer`, `ByteArrayOutputStream`, `InetSocketAddress`, `CountDownLatch`, `AtomicInteger`, `AtomicReference`, `LockSupport`

---

### Task 1: Create the new failing happy-path test in the dedicated package

**Files:**
- Create: `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java`
- Test: `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java`

- [ ] **Step 1: Write the failing test**

```java
package yier.bubu.jdk.nio.nonblocking;

import org.junit.Assert;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class NonBlockingEchoRoundTripTest {
    private static final long TIMEOUT_MILLIS = 6000L;

    @Test
    public void exchange_shouldAckHello() throws Exception {
        AtomicInteger portRef = new AtomicInteger(-1);
        CountDownLatch portReady = new CountDownLatch(1);
        AtomicReference<Throwable> failureRef = new AtomicReference<>();

        NonBlockingEchoServer server = new NonBlockingEchoServer();
        NonBlockingEchoClient client = new NonBlockingEchoClient();

        Thread serverThread = startServer(server, portRef, portReady, failureRef);
        try {
            awaitPort(portReady, portRef);
            String response = client.exchange(loopback(portRef.get()), "hello");
            Assert.assertEquals("ACK:hello", response);
            awaitServerExit(serverThread, failureRef);
        } finally {
            serverThread.join(TIMEOUT_MILLIS);
        }
    }

    private static Thread startServer(NonBlockingEchoServer server,
                                      AtomicInteger portRef,
                                      CountDownLatch portReady,
                                      AtomicReference<Throwable> failureRef) {
        Thread thread = new Thread(() -> {
            try {
                server.serveOnce(portRef, portReady);
            } catch (Throwable t) {
                failureRef.compareAndSet(null, t);
            }
        }, "nonblocking-echo-server");
        thread.start();
        return thread;
    }

    private static void awaitPort(CountDownLatch portReady, AtomicInteger portRef) throws InterruptedException {
        Assert.assertTrue("server should publish a port", portReady.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        Assert.assertTrue("port should be assigned", portRef.get() > 0);
    }

    private static void awaitServerExit(Thread serverThread, AtomicReference<Throwable> failureRef) throws Exception {
        serverThread.join(TIMEOUT_MILLIS);
        Assert.assertFalse("server thread should exit", serverThread.isAlive());
        Throwable failure = failureRef.get();
        if (failure == null) {
            return;
        }
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new RuntimeException(failure);
    }

    private static InetSocketAddress loopback(int port) throws Exception {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl jdk -am -Dtest=NonBlockingEchoRoundTripTest#exchange_shouldAckHello -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL during test compilation because `NonBlockingEchoServer` and `NonBlockingEchoClient` do not exist yet.

- [ ] **Step 3: Commit**

```bash
git add jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java
git commit -m "test(jdk): add non-blocking echo round-trip test"
```

### Task 2: Implement minimal server and client classes for the happy path

**Files:**
- Create: `jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoServer.java`
- Create: `jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoClient.java`
- Test: `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java`

- [ ] **Step 1: Write the minimal server implementation**

```java
package yier.bubu.jdk.nio.nonblocking;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public final class NonBlockingEchoServer {
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final int READ_BUFFER_SIZE = 4;

    public void serveOnce(AtomicInteger portRef, CountDownLatch portReady) throws Exception {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            server.configureBlocking(false);
            portRef.set(server.socket().getLocalPort());
            portReady.countDown();

            long deadline = System.nanoTime() + TIMEOUT_NANOS;
            try (SocketChannel client = awaitAccepted(server, deadline)) {
                client.configureBlocking(false);
                String request = readLine(client, deadline);
                if (request.isEmpty()) {
                    throw new IllegalStateException("empty payload not supported yet");
                }
                writeFully(client, "ACK:" + request + "\n", deadline);
            }
        } finally {
            portReady.countDown();
        }
    }

    // Keep helper methods private in this class:
    // awaitAccepted(...)
    // readLine(...)
    // writeFully(...)
    // pauseIfNeeded(...)
}
```

- [ ] **Step 2: Write the minimal client implementation**

```java
package yier.bubu.jdk.nio.nonblocking;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public final class NonBlockingEchoClient {
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final int READ_BUFFER_SIZE = 4;

    public String exchange(InetSocketAddress address, String message) throws Exception {
        try (SocketChannel channel = SocketChannel.open()) {
            channel.configureBlocking(false);
            channel.connect(address);

            long deadline = System.nanoTime() + TIMEOUT_NANOS;
            awaitConnected(channel, deadline);
            writeFully(channel, message + "\n", deadline);
            return readLine(channel, deadline);
        }
    }

    // Keep helper methods private in this class:
    // awaitConnected(...)
    // readLine(...)
    // writeFully(...)
    // pauseIfNeeded(...)
}
```

- [ ] **Step 3: Fill in the private helper methods**

Implementation requirements:

- `NonBlockingEchoServer` must visibly poll `accept()`, `read()`, and `write()`
- `NonBlockingEchoClient` must visibly poll `finishConnect()`, `read()`, and `write()`
- use `ByteArrayOutputStream` or equivalent simple accumulation for line assembly
- each class keeps its own `readLine`, `writeFully`, and `pauseIfNeeded` helpers
- use `LockSupport.parkNanos(...)` plus deadline checks for no-progress cases
- keep the minimal implementation intentionally focused on the non-empty happy path so Task 3 still has a meaningful red step

- [ ] **Step 4: Run the happy-path test to verify it passes**

Run: `mvn -pl jdk -am -Dtest=NonBlockingEchoRoundTripTest#exchange_shouldAckHello -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS with one executed test.

- [ ] **Step 5: Commit**

```bash
git add jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoServer.java jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoClient.java jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java
git commit -m "feat(jdk): add non-blocking echo client and server"
```

### Task 3: Add the empty-payload regression test

**Files:**
- Modify: `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java`
- Test: `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java`

- [ ] **Step 1: Write the failing empty-payload test**

```java
@Test
public void exchange_shouldAckEmptyPayload() throws Exception {
    AtomicInteger portRef = new AtomicInteger(-1);
    CountDownLatch portReady = new CountDownLatch(1);
    AtomicReference<Throwable> failureRef = new AtomicReference<>();

    NonBlockingEchoServer server = new NonBlockingEchoServer();
    NonBlockingEchoClient client = new NonBlockingEchoClient();

    Thread serverThread = startServer(server, portRef, portReady, failureRef);
    try {
        awaitPort(portReady, portRef);
        String response = client.exchange(loopback(portRef.get()), "");
        Assert.assertEquals("ACK:", response);
        awaitServerExit(serverThread, failureRef);
    } finally {
        serverThread.join(TIMEOUT_MILLIS);
    }
}
```

- [ ] **Step 2: Run the empty-payload test to verify it fails**

Run: `mvn -pl jdk -am -Dtest=NonBlockingEchoRoundTripTest#exchange_shouldAckEmptyPayload -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because the minimal server implementation still rejects a newline-only payload.

- [ ] **Step 3: Commit**

```bash
git add jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java
git commit -m "test(jdk): cover empty non-blocking echo payload"
```

### Task 4: Finish the refactor, support empty payloads, and remove the old demo wrapper

**Files:**
- Modify: `jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoServer.java`
- Modify: `jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoClient.java`
- Modify: `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java` (only if helper cleanup is needed)
- Delete: `jdk/src/main/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemo.java`
- Delete: `jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java`

- [ ] **Step 1: Update the server and client to satisfy the full spec**

Implementation changes:

- remove the temporary non-empty guard from `NonBlockingEchoServer`
- ensure newline-only input is treated as a complete request and returns `ACK:`
- add class-level Javadocs to both classes explaining that this package demonstrates non-blocking sockets without `Selector`
- keep all helper methods private to their own class
- do not add `main` methods

- [ ] **Step 2: Delete the old single-class demo and old test**

Delete:

- `jdk/src/main/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemo.java`
- `jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java`

- [ ] **Step 3: Run the focused refactor tests**

Run: `mvn -pl jdk -am -Dtest=NonBlockingEchoRoundTripTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS with both `exchange_shouldAckHello` and `exchange_shouldAckEmptyPayload` green.

- [ ] **Step 4: Commit**

```bash
git add jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoServer.java jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoClient.java jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java jdk/src/main/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemo.java jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java
git commit -m "refactor(jdk): split non-blocking echo client and server"
```

### Task 5: Run module-level verification and confirm the old wrapper is gone

**Files:**
- Verify only: `jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoServer.java`
- Verify only: `jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoClient.java`
- Verify only: `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java`
- Verify only: `jdk/src/main/java/yier/bubu/jdk/nio/NioReactorEchoServer.java`

- [ ] **Step 1: Run the targeted `jdk` tests**

Run: `mvn -pl jdk -am test -Dtest=StreamSamplesTest,NonBlockingEchoRoundTripTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS for the existing `StreamSamplesTest` and the new non-blocking echo tests.

- [ ] **Step 2: Build the module**

Run: `mvn -pl jdk -am -DskipTests package`

Expected: BUILD SUCCESS and compiled classes under `jdk/target/classes/yier/bubu/jdk/nio/nonblocking/`.

- [ ] **Step 3: Verify the old wrapper files are absent**

Run:

```bash
test ! -e jdk/src/main/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemo.java
test ! -e jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java
```

Expected: both commands exit successfully.
