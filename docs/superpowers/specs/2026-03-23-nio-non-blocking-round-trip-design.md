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
- Move all round-trip orchestration into tests
- Preserve the teaching focus on non-blocking `accept`, `finishConnect`, `read`, and `write`
- Keep the example small enough to read quickly

## Non-Goals

- No `Selector`, `SelectionKey`, or Reactor abstractions
- No `main` methods in the new non-blocking example classes
- No shared support/helper class extracted just to remove a small amount of duplication
- No multi-client server
- No production-ready socket framework
- No new dependencies

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

Delete:

- `jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java`

The test should own the closed-loop behavior instead of calling a demo wrapper hidden in production
code.

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
- bind to loopback on port `0`
- switch to non-blocking mode
- publish the chosen port to the caller
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

- one public method that performs a single server run, for example `serveOnce(...)`

The exact parameter list may be adjusted during planning, but it must support test-owned port
publication and failure propagation.

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

## No Shared Support Class

Do not introduce a third production class such as `NonBlockingIoSupport`.

If `NonBlockingEchoServer` and `NonBlockingEchoClient` each need small private helpers like:

- `readLine(...)`
- `writeFully(...)`
- `pauseIfNeeded(...)`

those helpers should stay inside their respective classes.

Some duplication is acceptable here because the primary goal is clarity of each role, not maximum
deduplication.

## Non-Blocking Semantics to Show

The refactor must continue to make these behaviors visible:

- server `accept()` can return `null`
- client `finishConnect()` may need retries
- `read()` can return `0`
- `write()` can return `0` or partial progress

The code should not hide these behaviors behind a framework-like abstraction.

## Runtime Structure

The end-to-end round trip should be assembled in the test, not in production code.

The test should:

- create the server object
- create the client object
- start the server on a dedicated thread
- wait for the server to publish its port
- call the client exchange method
- join the server thread
- rethrow any server-side failure captured from the background thread

The production classes should stay usable independently of JUnit, but the closed-loop scenario
itself belongs in the test.

## Error Handling

Both production classes should fail fast and clearly.

Required rules:

- every polling loop has a deadline
- timeout results in `IllegalStateException`
- `read() == -1` before a full line is received is a failure
- channels are closed with try-with-resources or equivalent `finally` handling

For the server thread used in tests:

- background exceptions should be captured into an `AtomicReference<Throwable>`
- the test should fail if that reference is non-null after the thread completes

## Backoff Strategy

When a non-blocking operation makes no progress, each class should briefly pause with:

- `LockSupport.parkNanos(...)`

Use a small fixed backoff only. No scheduler, executor, or adaptive retry policy is needed.

## Testing Strategy

The tests should exercise real loopback channels rather than mocks.

Create one JUnit 4 test class with at least these scenarios.

### Happy Path

Test flow:

- start the server on a background thread
- wait for port publication
- run the client exchange with `"hello"`
- assert the response is `ACK:hello`
- verify the server thread exits cleanly

### Empty Payload

Test flow:

- start the server on a background thread
- wait for port publication
- run the client exchange with `""`
- assert the response is `ACK:`
- verify the server thread exits cleanly

The tests are responsible for the lifecycle of the round-trip scenario.

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
- Keep helper logic private to each class instead of extracting a shared support type
- Prefer focused classes over a single demo wrapper

## Planned Files

- `jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoServer.java`
- `jdk/src/main/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoClient.java`
- `jdk/src/test/java/yier/bubu/jdk/nio/nonblocking/NonBlockingEchoRoundTripTest.java`
- delete `jdk/src/main/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemo.java`
- delete `jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java`
