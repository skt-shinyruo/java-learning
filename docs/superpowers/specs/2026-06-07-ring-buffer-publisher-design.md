# Ring Buffer Publisher Design

## 1. Purpose

Build a JVM in-process, high-performance producer/publisher framework in the
`concurrency` module. The framework is a learning-oriented "Disruptor Lite":
it focuses on ring-buffer sequencing, preallocated event slots, backpressure,
wait strategies, and broadcast consumption.

This is not a general message bus. The first version does not include topics,
cross-process delivery, persistence, acknowledgements, replay, transactions, or
dynamic subscription management.

## 2. Scope

The first implementation delivers the broadcast core:

- A fixed-size ring buffer with capacity constrained to powers of two.
- Preallocated event slots created by an `EventFactory<T>`.
- Single-producer and multi-producer sequencers.
- Blocking, yielding, busy-spin, and sleeping wait strategies.
- Blocking, non-blocking, and timeout publish APIs.
- Multiple broadcast consumers, each receiving every published event.
- Consumer exception handling with a default continue-on-error policy.
- Unit tests and a source documentation page under `concurrency/docs/`.

Later phases may add worker-pool competitive consumption and consumer dependency
graphs. Those extension points should be visible in the design but not required
for the first implementation.

## 3. Module and Package

Place the framework in:

- `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/`
- `concurrency/src/test/java/yier/bubu/concurrency/ringbuffer/`
- `concurrency/docs/ring-buffer-publisher.md`

Use `ringbuffer` as the package name because it describes the mechanism directly
and avoids presenting this as a full Disruptor clone.

## 4. Core Types

The core API is split into small, focused types:

- `RingBuffer<T>`: fixed-capacity event-slot array and public publish/access API.
- `EventFactory<T>`: creates preallocated event objects at startup.
- `EventTranslator<T>`: writes business data into a claimed event slot.
- `Sequencer`: allocates publish sequences, tracks availability, and enforces
  backpressure.
- `SingleProducerSequencer`: optimized sequencer for one publishing thread.
- `MultiProducerSequencer`: CAS-based sequencer for multiple publishing threads.
- `Sequence`: padded sequence value wrapper used by producers and consumers.
- `SequenceBarrier`: consumer-side wait boundary for available sequences.
- `WaitStrategy`: pluggable waiting behavior.
- `BatchEventProcessor<T>`: consumer event loop that processes contiguous batches.
- `EventHandler<T>`: user callback for handling events.
- `ExceptionHandler<T>`: user callback for consumer failures.

Future extension types:

- `WorkerPool<T>`: competitive-consumption layer where each event is handled by
  one worker.
- `EventProcessorGroup<T>`: dependency-topology API for later `then()` style
  stages.

## 5. Public API Shape

Example usage:

```java
RingBuffer<OrderEvent> ringBuffer = RingBuffer.createMultiProducer(
        new OrderEventFactory(),
        1024,
        new BlockingWaitStrategy());

BatchEventProcessor<OrderEvent> processor = new BatchEventProcessor<OrderEvent>(
        ringBuffer,
        ringBuffer.newBarrier(),
        new OrderEventHandler(),
        new LoggingExceptionHandler<OrderEvent>());

ringBuffer.addGatingSequences(processor.getSequence());
processor.start();

ringBuffer.publish(new EventTranslator<OrderEvent>() {
    @Override
    public void translateTo(OrderEvent event, long sequence) {
        event.setOrderId("A-1001");
        event.setAmount(99L);
    }
});
```

Publishing API:

- `void publish(EventTranslator<T> translator)`
- `boolean tryPublish(EventTranslator<T> translator)`
- `boolean publish(EventTranslator<T> translator, long timeout, TimeUnit unit)`
- `long next()`
- `long tryNext()` throws `InsufficientCapacityException` when no slot is
  immediately available.
- `void publish(long sequence)`
- `T get(long sequence)`

The translator-style API is the main path because it keeps event objects
preallocated and avoids per-message allocation. Lower-level `next()`, `get()`,
and `publish(sequence)` are kept for advanced examples and tests.

## 6. Publish Flow

A normal publish follows this sequence:

1. The producer claims a sequence through `next()`, `tryNext()`, or timeout
   claiming.
2. `RingBuffer.get(sequence)` maps the sequence to a slot with
   `sequence & (bufferSize - 1)`.
3. `EventTranslator<T>` writes business data into the preallocated event object.
4. `publish(sequence)` marks the sequence as available.
5. The sequencer signals the configured `WaitStrategy`.
6. Consumers waiting through a `SequenceBarrier` can observe and process the
   newly available sequence.

The ring buffer never creates event objects during publish.

For translator-style publishing, a claimed sequence must always be published in
a `finally` block. If `EventTranslator<T>` throws, the publish method rethrows
the exception after making the claimed sequence visible. This avoids permanent
sequence gaps. Translators should validate inputs before claiming when partial
event mutation would be harmful.

## 7. Sequencing and Backpressure

The `Sequencer` owns sequence allocation and publish visibility.

For single producer:

- Keep `nextValue` and `cachedGatingSequence` as ordinary fields.
- Claiming a sequence does not need CAS.
- When the claimed sequence would wrap past the slowest consumer, refresh the
  minimum gating sequence.
- If no slot is available, `publish()` waits, `tryPublish()` fails, and
  timeout publish waits up to the requested deadline.

For multiple producers:

- Claim sequences with CAS on the producer cursor.
- Track slot availability separately from the cursor so that consumers do not
  pass gaps caused by out-of-order producer publication.
- Provide `isAvailable(sequence)` and `getHighestPublishedSequence(next,
  available)` semantics.

The first version does not support overwrite, drop, or sampling behavior when
the ring is full. Full buffers apply backpressure to producers.

Add a checked or runtime `InsufficientCapacityException` for low-level
non-blocking claim failures. The higher-level `tryPublish(...)` API should catch
that condition and return `false`.

## 8. Consumer Model

The first version implements broadcast consumption:

- Each `BatchEventProcessor<T>` owns one consumer `Sequence`.
- Each processor receives every published event.
- The ring buffer uses all consumer sequences as gating sequences.
- The slowest consumer determines when producers can reuse old slots.

The consumer event loop:

1. Compute `nextSequence = currentSequence + 1`.
2. Call `SequenceBarrier.waitFor(nextSequence)`.
3. Process all contiguous available events up to `availableSequence`.
4. Update the processor's `Sequence`.
5. Exit when `halt()` is requested.

Batching should be explicit in the implementation: when a barrier returns a
higher available sequence, the processor handles the whole contiguous range
before waiting again.

## 9. Wait Strategies

Provide a `WaitStrategy` interface and these implementations:

- `BlockingWaitStrategy`: default strategy for normal tests and examples.
- `YieldingWaitStrategy`: spins briefly, then uses `Thread.yield()`.
- `BusySpinWaitStrategy`: continuously spins for low latency experiments.
- `SleepingWaitStrategy`: combines spin, yield, and short sleep.

The same ring buffer and processor code should work with any strategy.

## 10. Exception Handling

Consumer exceptions are handled through `ExceptionHandler<T>`.

The default handler logs or records the exception and lets the processor advance
past the failed event. This prevents one handler failure from permanently
blocking producers through an unchanged gating sequence.

Provide an alternate fatal handler that halts the processor after a failure.
Retry policies are out of scope for the first version because they complicate
ordering and duplicate-processing semantics.

## 11. Testing Plan

Unit tests should cover:

- Ring buffer capacity must be a power of two.
- Slots are preallocated and reused.
- Single producer publishes a complete ordered sequence.
- Multiple broadcast consumers each receive the complete sequence.
- `tryPublish()` returns `false` when the ring is full.
- Timeout publish returns `false` after the requested wait when the ring is full.
- A slow consumer applies backpressure through gating sequences.
- Multi-producer out-of-order publish does not allow consumers to pass gaps.
- Wait strategies can be swapped without changing ring-buffer behavior.
- Blocking wait strategy wakes consumers after publish.
- Default exception handler lets processor sequence advance.
- Fatal exception handler halts the processor.
- `halt()` stops processors and does not leak test threads.

Use JUnit 4 and keep tests deterministic. Avoid probabilistic race assertions in
normal unit tests.

## 12. Documentation Plan

Add `concurrency/docs/ring-buffer-publisher.md` explaining:

- The relationship between ring buffer slots, cursor, gating sequences, and
  consumer sequences.
- Why buffer size must be a power of two.
- How preallocated event slots reduce GC pressure.
- How single-producer and multi-producer sequencers differ.
- How broadcast consumers differ from worker-pool competitive consumption.
- Why this framework is JVM in-process only and does not provide durable
  messaging guarantees.

## 13. Verification

Run:

```bash
mvn -pl concurrency test
```

If shared module code is changed later, also run:

```bash
mvn test
```
