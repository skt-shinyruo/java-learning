# NIO Non-Blocking Round Trip Design

## Background

The `jdk` module already contains a more advanced NIO example:

- `jdk/src/main/java/yier/bubu/jdk/nio/NioReactorEchoServer.java`

That example is useful for learning `Selector`-based multiplexing and the Boss/Worker Reactor
pattern, but it is not the right teaching artifact for someone who wants to understand the
behavior of non-blocking network sockets themselves.

This design adds a second NIO example focused on the lower-level semantics of:

- `ServerSocketChannel.configureBlocking(false)`
- `SocketChannel.configureBlocking(false)`
- non-blocking `connect()`
- non-blocking `finishConnect()`
- non-blocking `accept()`
- non-blocking `read()` and `write()`

The goal is to show a minimal closed loop between a client and a server without introducing
`Selector`-based multiplexing.

## Goals

- Add a minimal educational NIO example under the `jdk` module
- Demonstrate non-blocking client and server socket behavior without using `Selector`
- Show a full loopback round trip from client request to server response
- Make the example executable from a single `main` method
- Make the core behavior testable without depending on console output
- Keep the code small enough to read in one sitting

## Non-Goals

- No `Selector`, `SelectionKey`, or Reactor abstractions
- No attempt to build a production-ready socket framework
- No multi-client handling
- No general-purpose protocol framing layer
- No thread pool, executor, or asynchronous callback API
- No Netty-style pipeline or buffer abstractions

## Module and Package Placement

The new example should live in the existing `jdk` module because it teaches JDK-level networking
APIs rather than Netty, JVM internals, or concurrency primitives.

Package:

- `yier.bubu.jdk.nio`

Primary class:

- `jdk/src/main/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemo.java`

Test class:

- `jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java`

`JdkApp` should remain unchanged. This example should be a separate executable entry point, matching
the current pattern used by `NioReactorEchoServer`.

## Teaching Scope

This example should explicitly teach the following points:

- a non-blocking server `accept()` call can return `null`
- a non-blocking client `finishConnect()` may need to be retried
- a non-blocking `read()` call can return `0` when no data is currently available
- a non-blocking `write()` call may write only part of the outbound buffer
- progress loops need timeout protection and a small backoff to avoid unbounded busy spinning

This example should not hide those behaviors behind helper frameworks. The control flow should make
the polling semantics visible to readers.

## Round-Trip Protocol

The demo should use a deliberately tiny text protocol so the non-blocking socket behavior remains
the focus.

Client request:

- UTF-8 encoded payload
- terminated by `\n`

Server response:

- `ACK:` + payload + `\n`

Examples:

- request `hello\n` -> response `ACK:hello\n`
- request `\n` -> response `ACK:\n`

The server should treat the first newline as the end of the request and respond once per connection.

## Runtime Structure

The demo should use two threads:

- one server thread
- one client thread

This is still the smallest practical closed loop for a single-process demo because a server blocked
inside its accept/read/write progress loop cannot also drive the client in the same thread.

The example should avoid introducing extra coordination primitives beyond what is required to:

- pass the dynamically assigned port from the server to the client
- collect the server response for assertions
- surface failures cleanly across threads

## Server Flow

The server side should:

1. open a `ServerSocketChannel`
2. bind it to the loopback interface on port `0`
3. switch it to non-blocking mode
4. repeatedly call `accept()` until a client channel is returned or timeout is reached
5. switch the accepted `SocketChannel` to non-blocking mode
6. repeatedly call `read()` until a full newline-terminated request is assembled
7. build the response `ACK:<payload>\n`
8. repeatedly call `write()` until the response buffer is fully sent
9. close the accepted channel and server channel

If `accept()` returns `null` or `read()`/`write()` make no progress, the loop should briefly park
before retrying.

## Client Flow

The client side should:

1. open a `SocketChannel`
2. switch it to non-blocking mode
3. start `connect()` against the server's loopback address and chosen port
4. repeatedly call `finishConnect()` until the connection completes or timeout is reached
5. repeatedly call `write()` until the full request line is sent
6. repeatedly call `read()` until a newline-terminated response is assembled
7. return the decoded response text
8. close the channel

The client flow should visibly distinguish:

- not connected yet
- connected but request not fully written
- request sent but response not yet readable

## Public Entry Points

The class should expose two ways to run the example.

### `main(String[] args)`

Purpose:

- manual execution from the command line

Behavior:

- use a default payload such as `hello`
- run the closed-loop demo
- print the request and response
- print a short note describing which non-blocking behaviors the example is demonstrating

### Package-Visible Test Hook

Provide a package-visible static method:

- `static String runRoundTrip(String message) throws Exception`

Purpose:

- allow tests to verify behavior without parsing stdout

Behavior:

- accept the logical payload without the trailing newline
- execute one closed-loop request/response exchange
- return the logical response without the trailing newline

Examples:

- `runRoundTrip("hello")` returns `ACK:hello`
- `runRoundTrip("")` returns `ACK:`

## Error Handling

The example should fail fast and clearly when the expected non-blocking progress does not happen.

Required rules:

- every polling loop has a deadline
- a timeout results in `IllegalStateException`
- `read() == -1` before a full line is received is treated as premature peer close and fails
- background thread failures are propagated back to the caller
- all channels are closed in `finally` blocks or try-with-resources

This keeps the demo deterministic enough for tests and prevents hangs during local runs.

## Backoff Strategy

The demo should not use a pure tight spin loop. When a non-blocking operation cannot currently make
progress, it should pause briefly with:

- `LockSupport.parkNanos(...)`

The backoff should be small and fixed. The purpose is not sophisticated scheduling, only preventing
the teaching example from turning into a wasteful busy loop.

## Testing Strategy

Two focused JUnit 4 tests are sufficient.

### Happy Path

Test:

- `runRoundTrip("hello")` returns `ACK:hello`

Purpose:

- verifies the complete request/response path across non-blocking connect, accept, write, and read

### Empty Payload

Test:

- `runRoundTrip("")` returns `ACK:`

Purpose:

- verifies the protocol still works when the line contains no payload before the newline

The tests should exercise real channels on the loopback interface rather than mocks.

## Documentation Inside the Code

The class-level Javadoc should state clearly that:

- this is a non-blocking socket semantics example
- it intentionally avoids `Selector`
- it is meant to complement, not replace, `NioReactorEchoServer`

The Javadoc should also include an example command from the repository root:

- `mvn -pl jdk -am -DskipTests package`
- `java -cp jdk/target/classes yier.bubu.jdk.nio.NonBlockingSocketRoundTripDemo`

## Implementation Constraints

- Keep the code JDK 8 compatible
- Keep the implementation in a single production class unless a small helper type is clearly needed
- Prefer standard JDK classes only
- Do not modify `NioReactorEchoServer`
- Do not add dependencies
- Do not add a separate documentation file unless the implementation becomes too dense for comments

## Planned Files

- `jdk/src/main/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemo.java`
- `jdk/src/test/java/yier/bubu/jdk/nio/NonBlockingSocketRoundTripDemoTest.java`
