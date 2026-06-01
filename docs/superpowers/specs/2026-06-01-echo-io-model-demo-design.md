# Echo I/O Model Demo Design

## Goal

Add a teaching-oriented echo server/client example to the `nio` module. The example should use real local network I/O to explain the difference between synchronous, asynchronous, blocking, and non-blocking execution.

## Scope

- Add `EchoIoModelDemo` under `nio/src/main/java/yier/bubu/nio`.
- Add JUnit 4 tests under `nio/src/test/java/yier/bubu/nio`.
- Use loopback TCP sockets with `localhost:0` so tests do not depend on a fixed port.
- Cover four demo modes:
  - synchronous blocking
  - synchronous non-blocking
  - asynchronous blocking
  - asynchronous non-blocking
- Add an `echo` command to `NioDirectMemoryApp`.
- Update `nio/docs/io-sync-async-blocking-nonblocking.md` with commands and source links.

## Behavior

Each mode should send a small text payload such as `ping` and receive the same text back from the local echo server. Each mode returns an event log so tests and documentation can point to the important steps.

The command line shape should be:

```bash
java -cp nio/target/classes yier.bubu.nio.NioDirectMemoryApp echo sync-blocking
java -cp nio/target/classes yier.bubu.nio.NioDirectMemoryApp echo sync-nonblocking
java -cp nio/target/classes yier.bubu.nio.NioDirectMemoryApp echo async-blocking
java -cp nio/target/classes yier.bubu.nio.NioDirectMemoryApp echo async-nonblocking
```

The four modes should be implemented as follows:

- `sync-blocking`: use `ServerSocket` and `Socket`. The client writes the request and blocks on `read()` until the server writes the echo response.
- `sync-nonblocking`: use `ServerSocketChannel`, `SocketChannel.configureBlocking(false)`, and `Selector`. The client drives connection, write, and read readiness through selector events.
- `async-blocking`: use `AsynchronousServerSocketChannel` and `AsynchronousSocketChannel`, but have the client call `Future.get()` after starting asynchronous operations. This shows that an asynchronous API can still be used in a blocking style.
- `async-nonblocking`: use `AsynchronousServerSocketChannel`, `AsynchronousSocketChannel`, and `CompletionHandler`. The client completes through callbacks instead of blocking for the result.

## Error Handling

- Let I/O failures propagate as `IOException` where the public API is synchronous.
- Convert asynchronous failures into `IOException` or `ExecutionException` with clear context from the caller-facing method.
- Use bounded waits in tests to avoid hanging the test suite if an asynchronous callback never fires.
- Close sockets, channels, selectors, and executor resources in `finally` blocks or try-with-resources.

## Tests

- Each mode echoes the request payload back to the client.
- Each mode records event-log entries for connect, write, server echo, and client read.
- Synchronous blocking mode records a blocking read step.
- Synchronous non-blocking mode records selector/readiness-driven progress.
- Asynchronous blocking mode records that the client waits on `Future.get()`.
- Asynchronous non-blocking mode records callback completion.
- Tests avoid fixed ports by binding servers to `localhost:0`.

## Documentation

Update the I/O concept document with:

- the four command examples,
- a short explanation of what each demo proves,
- source and test paths for readers who want to inspect the implementation.

## Non-Goals

- Build a production echo server.
- Support multiple concurrent clients.
- Add a protocol beyond a single small UTF-8 request and response.
- Benchmark performance across I/O models.
- Introduce external dependencies.

