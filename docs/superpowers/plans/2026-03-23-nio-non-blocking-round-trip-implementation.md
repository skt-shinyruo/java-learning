# NIO Non-Blocking Echo Client/Server Simplification Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the committed `yier.bubu.jdk.nio.nonblocking` example so beginners see the non-blocking socket story first, while test-orchestration complexity is pushed out of the main round-trip path.

**Architecture:** Keep two production roles, `NonBlockingEchoServer` and `NonBlockingEchoClient`, but reshape the server API so it binds eagerly and exposes `port()` or `address()` directly. Split tests into a beginner-facing `NonBlockingEchoRoundTripTest` for the happy path and empty payload, plus a separate `NonBlockingEchoTimeoutTest` for timeout contracts, so the main teaching path no longer depends on `CountDownLatch + AtomicInteger + AtomicReference` bootstrapping.

**Tech Stack:** Java 8, Maven, JUnit 4, `ServerSocketChannel`, `SocketChannel`, `ByteBuffer`, `ByteArrayOutputStream`, `InetSocketAddress`, `FutureTask`, `TimeUnit`, `LockSupport`

---

### Task 1: Rewrite the round-trip tests to express the simpler API first

**Files:**
- Modify: `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java`
- Create: `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoTimeoutTest.java`
- Test: `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java`
- Test: `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoTimeoutTest.java`

- [ ] **Step 1: Rewrite the main round-trip test class as the desired API contract**

Replace `NonBlockingEchoRoundTripTest` so it:

- contains only the beginner-facing scenarios:
  - `exchange_shouldAckHello`
  - `exchange_shouldAckEmptyPayload`
- creates the server directly
- starts `server::serveOnce` on a background task
- uses `server.port()` or `server.address()` directly
- uses a straight-line helper like `FutureTask` / `Future.get()` instead of `CountDownLatch`, `AtomicInteger`, and `AtomicReference<Throwable>` for the main path

Minimal target shape:

```java
NonBlockingEchoServer server = new NonBlockingEchoServer();
FutureTask<Void> serverTask = runServer(server);
String response = client.exchange(loopback(server.port()), "hello");
Assert.assertEquals("ACK:hello", response);
awaitServer(serverTask);
```

- [ ] **Step 2: Move timeout-focused scenarios into a dedicated timeout test class**

Create `NonBlockingEchoTimeoutTest` and move these contract tests there:

- server accept timeout -> `IllegalStateException`
- client read timeout -> `IllegalStateException`

Keep any extra timeout scaffolding in that file only.

- [ ] **Step 3: Run tests to verify the new API contract fails for the right reason**

Run: `mvn -pl jdk -am -Dtest=NonBlockingEchoRoundTripTest,NonBlockingEchoTimeoutTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `NonBlockingEchoServer` does not yet expose the simpler setup API (`port()` / `address()` and no-arg `serveOnce()`), and the tests have been rewritten to require it.

- [ ] **Step 4: Commit**

```bash
git add jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoTimeoutTest.java
git commit -m "test(jdk): simplify non-blocking echo test flow"
```

### Task 2: Simplify the server API to match the beginner-facing test flow

**Files:**
- Modify: `jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoServer.java`
- Test: `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java`

- [ ] **Step 1: Refactor server setup into object state**

Refactor `NonBlockingEchoServer` so it:

- binds loopback:0 in the constructor or equivalent obvious initialization path
- keeps the `ServerSocketChannel` as internal state
- exposes either:
  - `int port()`
  - or `InetSocketAddress address()`
- changes `serveOnce(...)` into a no-argument `serveOnce()`

The main round-trip tests should no longer need to pass:

- `AtomicInteger`
- `CountDownLatch`

just to discover the port.

- [ ] **Step 2: Keep the non-blocking server logic explicit**

Preserve the teaching mechanics in `NonBlockingEchoServer`:

- visible `accept()` polling
- visible `read()` polling
- visible `write()` polling
- deadline + `LockSupport.parkNanos(...)`
- empty payload still supported

Do not add `main` methods or extra production classes in this task.

- [ ] **Step 3: Run the beginner-facing round-trip tests**

Run: `mvn -pl jdk -am -Dtest=NonBlockingEchoRoundTripTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS with the two main flow tests green.

- [ ] **Step 4: Commit**

```bash
git add jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoServer.java jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java
git commit -m "refactor(jdk): simplify non-blocking echo server setup"
```

### Task 3: Simplify the test runtime model without hiding socket behavior

**Files:**
- Modify: `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java`
- Modify: `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoTimeoutTest.java`
- Test: `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java`
- Test: `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoTimeoutTest.java`

- [ ] **Step 1: Replace custom thread-failure plumbing in the main round-trip tests**

In `NonBlockingEchoRoundTripTest`, use a simpler helper pattern such as:

- `FutureTask<Void>`
- `Callable<Void>`
- `Future.get()`

instead of custom `AtomicReference<Throwable>` plumbing.

The main round-trip file should read as a straightforward story:

1. create server
2. create client
3. run server once in the background
4. exchange one message
5. await server completion

- [ ] **Step 2: Allow the timeout test file to keep any necessary extra scaffolding**

`NonBlockingEchoTimeoutTest` may still use more explicit coordination when needed, but:

- keep that complexity confined to the timeout file
- avoid reintroducing it into the main round-trip test

- [ ] **Step 3: Run both test classes**

Run: `mvn -pl jdk -am -Dtest=NonBlockingEchoRoundTripTest,NonBlockingEchoTimeoutTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS with all round-trip and timeout tests green.

- [ ] **Step 4: Commit**

```bash
git add jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoTimeoutTest.java
git commit -m "refactor(jdk): isolate non-blocking timeout scaffolding"
```

### Task 4: Keep the client API simple while preserving timeout testability

**Files:**
- Modify: `jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoClient.java`
- Test: `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoTimeoutTest.java`

- [ ] **Step 1: Re-evaluate the current timeout seam for readability**

Keep or adjust the current package-visible timeout constructor based on this rule:

- if it is still the smallest way to support deterministic timeout tests, keep it
- if the timeout tests can become simpler with a clearer package-visible seam, change it minimally

Do not widen the public API unless strictly necessary.

- [ ] **Step 2: Keep the client story beginner-friendly**

The default teaching path in `NonBlockingEchoClient` should still be:

- `configureBlocking(false)`
- `connect(...)`
- `awaitConnected(...)`
- `writeFully(...)`
- `readLine(...)`

Avoid adding extra generality that makes the class harder to read.

- [ ] **Step 3: Run timeout-focused tests**

Run: `mvn -pl jdk -am -Dtest=NonBlockingEchoTimeoutTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS for both timeout contract tests.

- [ ] **Step 4: Commit**

```bash
git add jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoClient.java jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoTimeoutTest.java
git commit -m "refactor(jdk): keep non-blocking echo client readable"
```

### Task 5: Final `jdk` verification for the simplified teaching shape

**Files:**
- Verify only: `jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoServer.java`
- Verify only: `jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoClient.java`
- Verify only: `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java`
- Verify only: `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoTimeoutTest.java`
- Verify only: `jdk/src/main/java/yier/bubu/jdk/nio/NioReactorEchoServer.java`

- [ ] **Step 1: Run the targeted `jdk` tests**

Run: `mvn -pl jdk -am test -Dtest=StreamSamplesTest,NonBlockingEchoRoundTripTest,NonBlockingEchoTimeoutTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS for existing `StreamSamplesTest`, simplified round-trip tests, and timeout contract tests.

- [ ] **Step 2: Build the module**

Run: `mvn -pl jdk -am -DskipTests package`

Expected: BUILD SUCCESS.

- [ ] **Step 3: Verify old wrapper files remain absent**

Run:

```bash
test ! -e jdk/src/main/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemo.java
test ! -e jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java
```

Expected: both commands exit successfully.
