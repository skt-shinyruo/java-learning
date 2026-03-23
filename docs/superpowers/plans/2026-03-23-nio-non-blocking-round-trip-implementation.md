# NIO Non-Blocking Round Trip Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a small JDK 8 NIO example that demonstrates non-blocking client/server `SocketChannel` behavior without `Selector`, backed by executable tests.

**Architecture:** Implement one production class in `yier.bubu.jdk.nio` with a package-visible `runRoundTrip(String)` test hook and a standalone `main`. The demo will run a server thread and a client thread on loopback, use non-blocking `ServerSocketChannel` and `SocketChannel`, and explicitly poll `accept()`, `finishConnect()`, `read()`, and `write()` with deadlines plus small `LockSupport.parkNanos(...)` backoff.

**Tech Stack:** Java 8, Maven, JUnit 4, java.nio channels, `ByteBuffer`, `CountDownLatch`, `AtomicReference`, `LockSupport`

---

### Task 1: Add the first failing round-trip test

**Files:**
- Create: `jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java`
- Test: `jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java`

- [ ] **Step 1: Write the failing test**

```java
package yier.bubu.jdk.nio;

import org.junit.Assert;
import org.junit.Test;

public class NonBlockingSocketRoundTripDemoTest {
    @Test
    public void runRoundTrip_shouldAckHello() throws Exception {
        Assert.assertEquals("ACK:hello", NonBlockingSocketRoundTripDemo.runRoundTrip("hello"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl jdk -Dtest=NonBlockingSocketRoundTripDemoTest#runRoundTrip_shouldAckHello test`

Expected: FAIL during test compilation because `NonBlockingSocketRoundTripDemo` does not exist yet.

- [ ] **Step 3: Commit**

```bash
git add jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java
git commit -m "test(jdk): add first non-blocking round-trip test"
```

### Task 2: Implement the minimal non-blocking round trip for the happy path

**Files:**
- Create: `jdk/src/main/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemo.java`
- Test: `jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java`

- [ ] **Step 1: Write minimal implementation**

```java
package yier.bubu.jdk.nio;

import java.util.concurrent.CountDownLatch;

public final class NonBlockingSocketRoundTripDemo {
    private NonBlockingSocketRoundTripDemo() {
    }

    static String runRoundTrip(String message) throws Exception {
        // Minimal implementation target:
        // 1. start a non-blocking server on loopback:0
        // 2. wait for the port to become available
        // 3. connect a non-blocking client
        // 4. send message + "\n"
        // 5. receive "ACK:" + message + "\n"
        // 6. return the response without trailing newline
        return "";
    }
}
```

- [ ] **Step 2: Fill in the implementation details**

Implementation requirements:

- use `ServerSocketChannel` and `SocketChannel`
- call `configureBlocking(false)` on both server and client channels
- bind server to `InetAddress.getLoopbackAddress()` and port `0`
- poll `accept()` until a client arrives
- poll `finishConnect()` until the client connects
- send and receive newline-terminated UTF-8 payloads
- loop on `read()` and `write()` until complete
- use deadline checks and `LockSupport.parkNanos(...)` for no-progress cases
- propagate server-thread failures back to the caller

- [ ] **Step 3: Run test to verify it passes**

Run: `mvn -pl jdk -Dtest=NonBlockingSocketRoundTripDemoTest#runRoundTrip_shouldAckHello test`

Expected: PASS with one executed test.

- [ ] **Step 4: Commit**

```bash
git add jdk/src/main/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemo.java jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java
git commit -m "feat(jdk): add non-blocking socket round-trip demo"
```

### Task 3: Add the empty-payload edge-case test

**Files:**
- Modify: `jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java`
- Test: `jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
public void runRoundTrip_shouldAckEmptyPayload() throws Exception {
    Assert.assertEquals("ACK:", NonBlockingSocketRoundTripDemo.runRoundTrip(""));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl jdk -Dtest=NonBlockingSocketRoundTripDemoTest#runRoundTrip_shouldAckEmptyPayload test`

Expected: FAIL because the current implementation mishandles a newline-only request or response.

- [ ] **Step 3: Commit**

```bash
git add jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java
git commit -m "test(jdk): cover empty non-blocking payload"
```

### Task 4: Extend the implementation to cover the edge case and polish the example entry point

**Files:**
- Modify: `jdk/src/main/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemo.java`
- Test: `jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java`

- [ ] **Step 1: Update the implementation minimally**

Implementation changes:

- ensure the server treats the first newline as a complete request even when the payload is empty
- ensure the response is decoded without trailing newline for test assertions
- add class-level Javadoc that explains this example intentionally avoids `Selector`
- add `main(String[] args)` that runs the default `hello` payload and prints request/response plus a short teaching note

- [ ] **Step 2: Run the focused tests**

Run: `mvn -pl jdk -Dtest=NonBlockingSocketRoundTripDemoTest test`

Expected: PASS with both tests green.

- [ ] **Step 3: Commit**

```bash
git add jdk/src/main/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemo.java jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java
git commit -m "feat(jdk): finish non-blocking round-trip example"
```

### Task 5: Run module-level verification

**Files:**
- Verify only: `jdk/src/main/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemo.java`
- Verify only: `jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java`
- Verify only: `jdk/src/main/java/yier/bubu/jdk/nio/NioReactorEchoServer.java`

- [ ] **Step 1: Run the targeted module tests**

Run: `mvn -pl jdk -am test -Dtest=StreamSamplesTest,NonBlockingSocketRoundTripDemoTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS for the existing `StreamSamplesTest` and the new NIO demo tests.

- [ ] **Step 2: Build the runnable example**

Run: `mvn -pl jdk -am -DskipTests package`

Expected: BUILD SUCCESS and compiled class available at `jdk/target/classes/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemo.class`.

- [ ] **Step 3: Smoke-test the main entry point**

Run: `java -cp jdk/target/classes yier.bubu.jdk.nio.NonBlockingSocketRoundTripDemo`

Expected output contains:

- `request=hello`
- `response=ACK:hello`
- a note mentioning non-blocking `accept`, `finishConnect`, `read`, and `write`

- [ ] **Step 4: Commit**

```bash
git add jdk/src/main/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemo.java jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java
git commit -m "test(jdk): verify non-blocking round-trip demo"
```
