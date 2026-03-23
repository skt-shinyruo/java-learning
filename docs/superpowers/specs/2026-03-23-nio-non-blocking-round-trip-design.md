# NIO Non-Blocking Echo Client/Server Design

## Background

The `jdk` module already contains a more advanced NIO example:

- `jdk/src/main/java/yier/bubu/jdk/nio/NioReactorEchoServer.java`

That class is useful for learning `Selector`-based multiplexing and the Boss/Worker Reactor
pattern, but it does not match the current teaching goal.

The current in-progress non-blocking demo also takes the wrong shape for the user's preferred
learning style:

- it combines server logic, client logic, test orchestration, and manual entry-point behavior in one
  production class
- it exposes a demo-style wrapper rather than clean client/server roles

This design replaces that structure with a small, dedicated package containing separate
non-blocking server and client classes, while moving all closed-loop orchestration into JUnit tests.

## Goals

- Keep the example in the `jdk` module
- Demonstrate non-blocking network I/O without using `Selector`
- Separate server and client into independent production classes
- Make the main round-trip tests easy for beginners to read
- Keep unavoidable complexity in the non-blocking socket code itself, not in test orchestration
- Preserve the teaching focus on non-blocking `accept`, `finishConnect`, `read`, and `write`
- Keep the example small enough to read quickly

## Non-Goals

- No `Selector`, `SelectionKey`, or Reactor abstractions
- No `main` methods in the new non-blocking example classes
- No multi-client server
- No production-ready socket framework
- No new dependencies
- No test-owned port publication protocol based on `CountDownLatch` + `AtomicInteger` when a simpler API can avoid it

## Module and Package Placement

The example should remain in `jdk` because it teaches raw JDK NIO networking APIs.

Create a dedicated package for this topic:

- `yier.bubu.jdk.nio.nonblocking`

This keeps the example clearly separate from the existing Reactor example in:

- `yier.bubu.jdk.nio.NioReactorEchoServer`

and avoids mixing two different teaching goals in the same package layer.

## Production Files

Create:

- `jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoServer.java`
- `jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoClient.java`

Delete:

- `jdk/src/main/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemo.java`

The old single-class demo should be removed rather than left in parallel, because it would duplicate
the same topic with a less desirable structure.

## Test Files

Create:

- `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java`
- `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoTimeoutTest.java`

Delete:

- `jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java`

The tests should still own execution, but the production API should be shaped so the main round-trip
tests stay straightforward and do not have to carry unnecessary synchronization noise.

## Round-Trip Protocol

Use the same tiny text protocol as before so the refactor changes structure, not topic.

Client request:

- UTF-8 payload
- newline terminated

Server response:

- `ACK:` + payload + newline

Examples:

- `hello\n` -> `ACK:hello\n`
- `\n` -> `ACK:\n`

The server handles one connection and one request line per run.

## Class Responsibilities

### `NonBlockingEchoServer`

Purpose:

- own the non-blocking server-side behavior only

Responsibilities:

- open a `ServerSocketChannel`
- bind to loopback on port `0` during construction or other obvious setup
- switch to non-blocking mode
- expose the chosen port or loopback address directly to callers
- accept exactly one client connection
- read exactly one newline-terminated request
- write exactly one `ACK:` response
- close resources and return

The server class should not:

- create the client
- create its own worker thread
- own the full end-to-end test scenario
- expose a `main`

Suggested API shape:

- a simple setup shape such as:
  - constructor binds and prepares the server
  - `int port()` or `InetSocketAddress address()`
  - `void serveOnce()`

The key design goal is that callers should not need `AtomicInteger` + `CountDownLatch` just to
learn what port the server bound to.

### `NonBlockingEchoClient`

Purpose:

- own the non-blocking client-side behavior only

Responsibilities:

- open a `SocketChannel`
- switch to non-blocking mode
- start a connection to a provided server address
- poll `finishConnect()`
- write one newline-terminated request
- read one newline-terminated response
- return the decoded response string without the trailing newline

The client class should not:

- start the server
- own any test synchronization concerns beyond its own exchange call
- expose a `main`

Suggested API shape:

- one public method such as `exchange(InetSocketAddress address, String message)`

## Keep Complexity In The Right Place

The complexity that should remain visible is the non-blocking socket behavior itself:

- `accept()` may return `null`
- `finishConnect()` may need retries
- `read()` may return `0`
- `write()` may make only partial progress

The complexity that should be reduced is test orchestration complexity that distracts beginners from
that main story.

In practice, that means:

- avoid exposing production APIs that force tests to pass around `AtomicInteger`, `CountDownLatch`,
  or similar coordination objects just to bootstrap a simple round trip
- keep helper methods or helper classes judged by whether they reduce reader confusion, not by
  whether they minimize line count

## Non-Blocking Semantics to Show

The refactor must continue to make these behaviors visible:

- server `accept()` can return `null`
- client `finishConnect()` may need retries
- `read()` can return `0`
- `write()` can return `0` or partial progress

The code should not hide these behaviors behind a framework-like abstraction.

## Runtime Structure

The end-to-end round trip should be assembled in the test, not in production code.

### Main Round-Trip Tests

The beginner-facing tests should read like the story they are teaching:

1. create server
2. create client
3. start server in the background
4. client sends a message to `server.port()` or `server.address()`
5. assert the response
6. wait for the server to finish

They should not require readers to first understand a coordination protocol built out of:

- `CountDownLatch`
- `AtomicInteger`
- `AtomicReference<Throwable>`

unless a particular test is explicitly about timeout/error behavior.

### Timeout Tests

Timeout-focused tests may use more scaffolding when needed, because they are checking contract
edges rather than teaching the main request/response path.

Those tests should be separated from the main round-trip tests so beginners can first read the
simple flow and only then inspect timeout-specific machinery.

## Error Handling

Both production classes should fail fast and clearly.

Required rules:

- every polling loop has a deadline
- timeout results in `IllegalStateException`
- `read() == -1` before a full line is received is a failure
- channels are closed with try-with-resources or equivalent `finally` handling

For main round-trip tests, prefer simpler exception propagation patterns such as:

- `FutureTask`
- `Future.get()`
- or another straight-line mechanism

instead of custom shared-state coordination when that shared state is not central to the lesson.

## Backoff Strategy

When a non-blocking operation makes no progress, each class should briefly pause with:

- `LockSupport.parkNanos(...)`

Use a small fixed backoff only. No scheduler, executor, or adaptive retry policy is needed.

## Testing Strategy

The tests should exercise real loopback channels rather than mocks.

Create two JUnit 4 test classes.

### Happy Path

Location:

- `NonBlockingEchoRoundTripTest`

Test flow:

- create server and client
- start server on a background thread
- run the client exchange with `"hello"` using the server's exposed address or port
- assert the response is `ACK:hello`
- verify the server finishes cleanly

### Empty Payload

Location:

- `NonBlockingEchoRoundTripTest`

Test flow:

- create server and client
- start server on a background thread
- run the client exchange with `""`
- assert the response is `ACK:`
- verify the server finishes cleanly

### Timeout Contracts

Location:

- `NonBlockingEchoTimeoutTest`

Test flow:

- include a server-side timeout case
- include a client-side timeout case
- assert timeout paths surface `IllegalStateException`

These tests may use extra test infrastructure when needed, but that infrastructure should stay out
of the main round-trip test class.

The tests are still responsible for lifecycle, but the main test class should remain easy to read
for someone who is learning non-blocking sockets for the first time.

## Documentation in Code

The class-level Javadocs for both production classes should explain:

- these classes are part of a non-blocking socket example
- the example intentionally avoids `Selector`
- the package complements, rather than replaces, `NioReactorEchoServer`

Do not include command-line usage sections tied to `main`, because the new design intentionally has
no `main` methods.

## Implementation Constraints

- Keep the code JDK 8 compatible
- Do not modify `NioReactorEchoServer`
- Do not add dependencies
- Prefer simpler, more direct APIs over test-driven coordination protocols
- Keep the main round-trip test class beginner-friendly
- If a timeout or failure contract needs extra scaffolding, isolate it in a dedicated timeout test class

## Planned Files

- `jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoServer.java`
- `jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoClient.java`
- `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java`
- `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoTimeoutTest.java`
- delete `jdk/src/main/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemo.java`
- delete `jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java`
