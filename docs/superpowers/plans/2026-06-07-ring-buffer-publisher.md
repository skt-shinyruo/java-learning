# Ring Buffer Publisher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a JVM in-process high-performance ring-buffer publisher framework in the `concurrency` module.

**Architecture:** The implementation is a small Disruptor-style core: a preallocated `RingBuffer<T>` delegates sequence allocation and availability to producer-specific `Sequencer` implementations, while consumers wait through `SequenceBarrier` and process batches through `BatchEventProcessor<T>`. The first delivery supports broadcast consumers, single-producer and multi-producer publishing, blocking/try/timeout publish APIs, pluggable wait strategies, and exception handlers.

**Tech Stack:** Java 8, JUnit 4, Maven Surefire, pure JDK concurrency primitives.

---

## File Structure

Create these production files under `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/`:

- `EventFactory.java`: creates preallocated event slots.
- `EventTranslator.java`: writes data into a claimed event slot.
- `EventHandler.java`: consumes events by sequence.
- `ExceptionHandler.java`: handles consumer callback failures.
- `InsufficientCapacityException.java`: low-allocation exception for `tryNext()` failures.
- `AlertException.java`: low-allocation exception for halted barriers.
- `Sequence.java`: padded volatile long wrapper with CAS support.
- `SequenceUtil.java`: minimum-sequence helper.
- `Sequencer.java`: sequence allocation and visibility contract.
- `AbstractSequencer.java`: common buffer-size, cursor, gating-sequence, and barrier code.
- `SingleProducerSequencer.java`: non-CAS producer sequencer.
- `MultiProducerSequencer.java`: CAS claim sequencer with contiguous publish cursor.
- `RingBuffer.java`: preallocated slot array and public publish API.
- `WaitStrategy.java`: consumer wait contract.
- `BlockingWaitStrategy.java`: condition-based wait strategy.
- `YieldingWaitStrategy.java`: spin-then-yield wait strategy.
- `BusySpinWaitStrategy.java`: continuous spin wait strategy.
- `SleepingWaitStrategy.java`: spin/yield/park wait strategy.
- `SequenceBarrier.java`: consumer wait boundary.
- `ProcessingSequenceBarrier.java`: default barrier implementation.
- `BatchEventProcessor.java`: broadcast consumer loop.
- `LoggingExceptionHandler.java`: default continue-on-error handler.
- `FatalExceptionHandler.java`: halt-by-throwing exception handler.

Create these test files under `concurrency/src/test/java/yier/bubu/concurrency/ringbuffer/`:

- `RingBufferConstructionTest.java`
- `SingleProducerPublishTest.java`
- `BackpressureTest.java`
- `WaitStrategyAndProcessorTest.java`
- `MultiProducerSequencerTest.java`
- `ExceptionHandlingTest.java`

Create this documentation file:

- `concurrency/docs/ring-buffer-publisher.md`

## Task 1: Core Interfaces, Sequence, and RingBuffer Construction

**Files:**
- Create: `concurrency/src/test/java/yier/bubu/concurrency/ringbuffer/RingBufferConstructionTest.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/EventFactory.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/EventTranslator.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/EventHandler.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/ExceptionHandler.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/InsufficientCapacityException.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/AlertException.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/Sequence.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/SequenceUtil.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/Sequencer.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/AbstractSequencer.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/SingleProducerSequencer.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/WaitStrategy.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/BlockingWaitStrategy.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/SequenceBarrier.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/ProcessingSequenceBarrier.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/RingBuffer.java`

- [ ] **Step 1: Write the failing construction tests**

Create `RingBufferConstructionTest.java` with these tests:

```java
package yier.bubu.concurrency.ringbuffer;

import org.junit.Assert;
import org.junit.Test;

public class RingBufferConstructionTest {
    @Test
    public void createSingleProducer_shouldRejectNonPowerOfTwoCapacity() {
        try {
            RingBuffer.createSingleProducer(new TestEventFactory(), 10, new BlockingWaitStrategy());
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("power of two"));
        }
    }

    @Test
    public void createSingleProducer_shouldPreallocateAndReuseSlots() {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 4, new BlockingWaitStrategy());

        TestEvent first = ringBuffer.get(0);
        TestEvent wrapped = ringBuffer.get(4);

        Assert.assertSame(first, wrapped);
        Assert.assertEquals(4, ringBuffer.getBufferSize());
        Assert.assertEquals(-1L, ringBuffer.getCursor());
    }

    private static final class TestEvent {
        private long value;
    }

    private static final class TestEventFactory implements EventFactory<TestEvent> {
        @Override
        public TestEvent newInstance() {
            return new TestEvent();
        }
    }
}
```

- [ ] **Step 2: Run the failing construction tests**

Run:

```bash
mvn -pl concurrency -Dtest=RingBufferConstructionTest test
```

Expected: FAIL because the `ringbuffer` package and `RingBuffer` API do not exist.

- [ ] **Step 3: Create the foundational API files**

Create these files exactly enough to compile the construction tests:

```java
// EventFactory.java
package yier.bubu.concurrency.ringbuffer;

public interface EventFactory<T> {
    T newInstance();
}
```

```java
// EventTranslator.java
package yier.bubu.concurrency.ringbuffer;

public interface EventTranslator<T> {
    void translateTo(T event, long sequence);
}
```

```java
// EventHandler.java
package yier.bubu.concurrency.ringbuffer;

public interface EventHandler<T> {
    void onEvent(T event, long sequence) throws Exception;
}
```

```java
// ExceptionHandler.java
package yier.bubu.concurrency.ringbuffer;

public interface ExceptionHandler<T> {
    void handleEventException(Throwable exception, long sequence, T event);
}
```

```java
// InsufficientCapacityException.java
package yier.bubu.concurrency.ringbuffer;

public final class InsufficientCapacityException extends Exception {
    public static final InsufficientCapacityException INSTANCE = new InsufficientCapacityException();

    private InsufficientCapacityException() {
        super("insufficient ring buffer capacity", null, false, false);
    }
}
```

```java
// AlertException.java
package yier.bubu.concurrency.ringbuffer;

public final class AlertException extends Exception {
    public static final AlertException INSTANCE = new AlertException();

    private AlertException() {
        super("sequence barrier alerted", null, false, false);
    }
}
```

```java
// Sequence.java
package yier.bubu.concurrency.ringbuffer;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;

public class Sequence {
    private static final long INITIAL_VALUE = -1L;
    private static final AtomicLongFieldUpdater<Sequence> VALUE_UPDATER =
            AtomicLongFieldUpdater.newUpdater(Sequence.class, "value");

    @SuppressWarnings("unused")
    private long p1, p2, p3, p4, p5, p6, p7;
    private volatile long value;
    @SuppressWarnings("unused")
    private long p8, p9, p10, p11, p12, p13, p14;

    public Sequence() {
        this(INITIAL_VALUE);
    }

    public Sequence(long initialValue) {
        this.value = initialValue;
    }

    public long get() {
        return value;
    }

    public void set(long value) {
        this.value = value;
    }

    public boolean compareAndSet(long expectedValue, long newValue) {
        return VALUE_UPDATER.compareAndSet(this, expectedValue, newValue);
    }
}
```

```java
// SequenceUtil.java
package yier.bubu.concurrency.ringbuffer;

public final class SequenceUtil {
    private SequenceUtil() {
    }

    public static long getMinimumSequence(Sequence[] sequences, long defaultValue) {
        long minimum = Long.MAX_VALUE;
        for (Sequence sequence : sequences) {
            long value = sequence.get();
            minimum = Math.min(minimum, value);
        }
        return minimum == Long.MAX_VALUE ? defaultValue : minimum;
    }

    public static int ceilingNextPowerOfTwo(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("value must be > 0");
        }
        int highestOneBit = Integer.highestOneBit(value);
        return value == highestOneBit ? value : highestOneBit << 1;
    }
}
```

- [ ] **Step 4: Create minimal sequencer and wait contracts**

Create:

```java
// Sequencer.java
package yier.bubu.concurrency.ringbuffer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public interface Sequencer {
    int getBufferSize();
    long getCursor();
    Sequence getCursorSequence();
    long next();
    long tryNext() throws InsufficientCapacityException;
    long next(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException;
    void publish(long sequence);
    boolean isAvailable(long sequence);
    long getHighestPublishedSequence(long lowerBound, long availableSequence);
    void addGatingSequences(Sequence... sequences);
    SequenceBarrier newBarrier();
}
```

```java
// WaitStrategy.java
package yier.bubu.concurrency.ringbuffer;

public interface WaitStrategy {
    long waitFor(long sequence,
                 Sequence cursorSequence,
                 Sequence dependentSequence,
                 SequenceBarrier barrier) throws AlertException, InterruptedException;

    void signalAllWhenBlocking();
}
```

```java
// SequenceBarrier.java
package yier.bubu.concurrency.ringbuffer;

public interface SequenceBarrier {
    long waitFor(long sequence) throws AlertException, InterruptedException;
    long getCursor();
    void alert();
    void clearAlert();
    boolean isAlerted();
    void checkAlert() throws AlertException;
}
```

```java
// ProcessingSequenceBarrier.java
package yier.bubu.concurrency.ringbuffer;

import java.util.concurrent.atomic.AtomicBoolean;

final class ProcessingSequenceBarrier implements SequenceBarrier {
    private final Sequencer sequencer;
    private final WaitStrategy waitStrategy;
    private final Sequence cursorSequence;
    private final Sequence dependentSequence;
    private final AtomicBoolean alerted = new AtomicBoolean(false);

    ProcessingSequenceBarrier(Sequencer sequencer,
                              WaitStrategy waitStrategy,
                              Sequence cursorSequence,
                              Sequence dependentSequence) {
        this.sequencer = sequencer;
        this.waitStrategy = waitStrategy;
        this.cursorSequence = cursorSequence;
        this.dependentSequence = dependentSequence;
    }

    @Override
    public long waitFor(long sequence) throws AlertException, InterruptedException {
        checkAlert();
        long availableSequence = waitStrategy.waitFor(sequence, cursorSequence, dependentSequence, this);
        if (availableSequence < sequence) {
            return availableSequence;
        }
        return sequencer.getHighestPublishedSequence(sequence, availableSequence);
    }

    @Override
    public long getCursor() {
        return dependentSequence.get();
    }

    @Override
    public void alert() {
        alerted.set(true);
        waitStrategy.signalAllWhenBlocking();
    }

    @Override
    public void clearAlert() {
        alerted.set(false);
    }

    @Override
    public boolean isAlerted() {
        return alerted.get();
    }

    @Override
    public void checkAlert() throws AlertException {
        if (alerted.get()) {
            throw AlertException.INSTANCE;
        }
    }
}
```

- [ ] **Step 5: Create minimal `BlockingWaitStrategy`, `AbstractSequencer`, `SingleProducerSequencer`, and `RingBuffer`**

Create:

```java
// BlockingWaitStrategy.java
package yier.bubu.concurrency.ringbuffer;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class BlockingWaitStrategy implements WaitStrategy {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition processorNotifyCondition = lock.newCondition();

    @Override
    public long waitFor(long sequence,
                        Sequence cursorSequence,
                        Sequence dependentSequence,
                        SequenceBarrier barrier) throws AlertException, InterruptedException {
        long availableSequence;
        if (cursorSequence.get() < sequence) {
            lock.lock();
            try {
                while ((availableSequence = cursorSequence.get()) < sequence) {
                    barrier.checkAlert();
                    processorNotifyCondition.await();
                }
            } finally {
                lock.unlock();
            }
        }

        while ((availableSequence = dependentSequence.get()) < sequence) {
            barrier.checkAlert();
            Thread.yield();
        }
        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking() {
        lock.lock();
        try {
            processorNotifyCondition.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
```

```java
// AbstractSequencer.java
package yier.bubu.concurrency.ringbuffer;

import java.util.Arrays;
import java.util.Objects;

abstract class AbstractSequencer implements Sequencer {
    protected final int bufferSize;
    protected final WaitStrategy waitStrategy;
    protected final Sequence cursor = new Sequence(-1L);
    private volatile Sequence[] gatingSequences = new Sequence[0];

    AbstractSequencer(int bufferSize, WaitStrategy waitStrategy) {
        if (bufferSize < 1 || Integer.bitCount(bufferSize) != 1) {
            throw new IllegalArgumentException("bufferSize must be a positive power of two");
        }
        this.bufferSize = bufferSize;
        this.waitStrategy = Objects.requireNonNull(waitStrategy, "waitStrategy");
    }

    @Override
    public int getBufferSize() {
        return bufferSize;
    }

    @Override
    public long getCursor() {
        return cursor.get();
    }

    @Override
    public Sequence getCursorSequence() {
        return cursor;
    }

    @Override
    public void addGatingSequences(Sequence... sequences) {
        Objects.requireNonNull(sequences, "sequences");
        for (Sequence sequence : sequences) {
            Objects.requireNonNull(sequence, "sequence");
        }
        synchronized (this) {
            Sequence[] current = gatingSequences;
            Sequence[] updated = Arrays.copyOf(current, current.length + sequences.length);
            System.arraycopy(sequences, 0, updated, current.length, sequences.length);
            gatingSequences = updated;
        }
    }

    @Override
    public SequenceBarrier newBarrier() {
        return new ProcessingSequenceBarrier(this, waitStrategy, cursor, cursor);
    }

    protected Sequence[] getGatingSequences() {
        return gatingSequences;
    }

    protected long getMinimumGatingSequence(long defaultValue) {
        return SequenceUtil.getMinimumSequence(gatingSequences, defaultValue);
    }
}
```

```java
// SingleProducerSequencer.java
package yier.bubu.concurrency.ringbuffer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

public final class SingleProducerSequencer extends AbstractSequencer {
    private long nextValue = -1L;
    private long cachedGatingSequence = -1L;

    public SingleProducerSequencer(int bufferSize, WaitStrategy waitStrategy) {
        super(bufferSize, waitStrategy);
    }

    @Override
    public long next() {
        long nextSequence = nextValue + 1L;
        waitForCapacity(nextSequence);
        nextValue = nextSequence;
        return nextSequence;
    }

    @Override
    public long tryNext() throws InsufficientCapacityException {
        long nextSequence = nextValue + 1L;
        if (!hasAvailableCapacity(nextSequence)) {
            throw InsufficientCapacityException.INSTANCE;
        }
        nextValue = nextSequence;
        return nextSequence;
    }

    @Override
    public long next(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        long nextSequence = nextValue + 1L;
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        while (!hasAvailableCapacity(nextSequence)) {
            if (System.nanoTime() >= deadlineNanos) {
                throw new TimeoutException("timed out waiting for ring buffer capacity");
            }
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            LockSupport.parkNanos(1L);
        }
        nextValue = nextSequence;
        return nextSequence;
    }

    @Override
    public void publish(long sequence) {
        cursor.set(sequence);
        waitStrategy.signalAllWhenBlocking();
    }

    @Override
    public boolean isAvailable(long sequence) {
        return sequence <= cursor.get();
    }

    @Override
    public long getHighestPublishedSequence(long lowerBound, long availableSequence) {
        return availableSequence;
    }

    private void waitForCapacity(long nextSequence) {
        while (!hasAvailableCapacity(nextSequence)) {
            LockSupport.parkNanos(1L);
        }
    }

    private boolean hasAvailableCapacity(long nextSequence) {
        long wrapPoint = nextSequence - bufferSize;
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue) {
            long minSequence = getMinimumGatingSequence(nextValue);
            cachedGatingSequence = minSequence;
            return wrapPoint <= minSequence;
        }
        return true;
    }
}
```

```java
// RingBuffer.java
package yier.bubu.concurrency.ringbuffer;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class RingBuffer<T> {
    private final Sequencer sequencer;
    private final Object[] entries;
    private final int indexMask;

    private RingBuffer(EventFactory<T> eventFactory, Sequencer sequencer) {
        this.sequencer = Objects.requireNonNull(sequencer, "sequencer");
        this.entries = new Object[sequencer.getBufferSize()];
        this.indexMask = sequencer.getBufferSize() - 1;
        for (int i = 0; i < entries.length; i++) {
            entries[i] = Objects.requireNonNull(eventFactory.newInstance(), "event");
        }
    }

    public static <T> RingBuffer<T> createSingleProducer(
            EventFactory<T> eventFactory,
            int bufferSize,
            WaitStrategy waitStrategy) {
        return new RingBuffer<T>(
                Objects.requireNonNull(eventFactory, "eventFactory"),
                new SingleProducerSequencer(bufferSize, waitStrategy));
    }

    public int getBufferSize() {
        return sequencer.getBufferSize();
    }

    public long getCursor() {
        return sequencer.getCursor();
    }

    @SuppressWarnings("unchecked")
    public T get(long sequence) {
        return (T) entries[(int) sequence & indexMask];
    }

    public void addGatingSequences(Sequence... sequences) {
        sequencer.addGatingSequences(sequences);
    }

    public SequenceBarrier newBarrier() {
        return sequencer.newBarrier();
    }

    public long next() {
        return sequencer.next();
    }

    public long tryNext() throws InsufficientCapacityException {
        return sequencer.tryNext();
    }

    public boolean publish(EventTranslator<T> translator, long timeout, TimeUnit unit) {
        Objects.requireNonNull(translator, "translator");
        try {
            long sequence = sequencer.next(timeout, unit);
            translateAndPublish(translator, sequence);
            return true;
        } catch (TimeoutException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void publish(EventTranslator<T> translator) {
        Objects.requireNonNull(translator, "translator");
        long sequence = sequencer.next();
        translateAndPublish(translator, sequence);
    }

    public boolean tryPublish(EventTranslator<T> translator) {
        Objects.requireNonNull(translator, "translator");
        final long sequence;
        try {
            sequence = sequencer.tryNext();
        } catch (InsufficientCapacityException exception) {
            return false;
        }
        translateAndPublish(translator, sequence);
        return true;
    }

    public void publish(long sequence) {
        sequencer.publish(sequence);
    }

    private void translateAndPublish(EventTranslator<T> translator, long sequence) {
        try {
            translator.translateTo(get(sequence), sequence);
        } finally {
            sequencer.publish(sequence);
        }
    }
}
```

- [ ] **Step 6: Run construction tests**

Run:

```bash
mvn -pl concurrency -Dtest=RingBufferConstructionTest test
```

Expected: PASS.

- [ ] **Step 7: Commit Task 1**

Run:

```bash
git add concurrency/src/main/java/yier/bubu/concurrency/ringbuffer concurrency/src/test/java/yier/bubu/concurrency/ringbuffer/RingBufferConstructionTest.java
git commit -m "feat: add ring buffer construction core"
```

## Task 2: Single-Producer Publishing and Backpressure

**Files:**
- Create: `concurrency/src/test/java/yier/bubu/concurrency/ringbuffer/SingleProducerPublishTest.java`
- Create: `concurrency/src/test/java/yier/bubu/concurrency/ringbuffer/BackpressureTest.java`
- Modify: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/RingBuffer.java`
- Modify: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/SingleProducerSequencer.java`

- [ ] **Step 1: Write single-producer publish tests**

Create `SingleProducerPublishTest.java`:

```java
package yier.bubu.concurrency.ringbuffer;

import org.junit.Assert;
import org.junit.Test;

public class SingleProducerPublishTest {
    @Test
    public void publish_shouldTranslateSlotAndAdvanceCursor() {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 4, new BlockingWaitStrategy());

        ringBuffer.publish(new EventTranslator<TestEvent>() {
            @Override
            public void translateTo(TestEvent event, long sequence) {
                event.value = 42L;
                event.sequenceSeenByTranslator = sequence;
            }
        });

        Assert.assertEquals(0L, ringBuffer.getCursor());
        Assert.assertEquals(42L, ringBuffer.get(0).value);
        Assert.assertEquals(0L, ringBuffer.get(0).sequenceSeenByTranslator);
    }

    @Test
    public void publish_shouldPublishClaimedSequenceWhenTranslatorThrows() {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 4, new BlockingWaitStrategy());

        try {
            ringBuffer.publish(new EventTranslator<TestEvent>() {
                @Override
                public void translateTo(TestEvent event, long sequence) {
                    event.value = 7L;
                    throw new IllegalStateException("translator failed");
                }
            });
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            Assert.assertEquals("translator failed", expected.getMessage());
        }

        Assert.assertEquals(0L, ringBuffer.getCursor());
        Assert.assertEquals(7L, ringBuffer.get(0).value);
    }

    private static final class TestEvent {
        private long value;
        private long sequenceSeenByTranslator;
    }

    private static final class TestEventFactory implements EventFactory<TestEvent> {
        @Override
        public TestEvent newInstance() {
            return new TestEvent();
        }
    }
}
```

- [ ] **Step 2: Write backpressure tests**

Create `BackpressureTest.java`:

```java
package yier.bubu.concurrency.ringbuffer;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class BackpressureTest {
    @Test
    public void tryPublish_shouldReturnFalseWhenRingIsFull() {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 2, new BlockingWaitStrategy());
        Sequence slowConsumer = new Sequence(-1L);
        ringBuffer.addGatingSequences(slowConsumer);

        Assert.assertTrue(ringBuffer.tryPublish(valueTranslator(1L)));
        Assert.assertTrue(ringBuffer.tryPublish(valueTranslator(2L)));
        Assert.assertFalse(ringBuffer.tryPublish(valueTranslator(3L)));

        slowConsumer.set(0L);
        Assert.assertTrue(ringBuffer.tryPublish(valueTranslator(3L)));
    }

    @Test
    public void publishWithTimeout_shouldReturnFalseWhenRingStaysFull() {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 1, new BlockingWaitStrategy());
        Sequence slowConsumer = new Sequence(-1L);
        ringBuffer.addGatingSequences(slowConsumer);

        Assert.assertTrue(ringBuffer.tryPublish(valueTranslator(1L)));
        Assert.assertFalse(ringBuffer.publish(valueTranslator(2L), 5L, TimeUnit.MILLISECONDS));
        Assert.assertEquals(0L, ringBuffer.getCursor());
    }

    private static EventTranslator<TestEvent> valueTranslator(final long value) {
        return new EventTranslator<TestEvent>() {
            @Override
            public void translateTo(TestEvent event, long sequence) {
                event.value = value;
            }
        };
    }

    private static final class TestEvent {
        private long value;
    }

    private static final class TestEventFactory implements EventFactory<TestEvent> {
        @Override
        public TestEvent newInstance() {
            return new TestEvent();
        }
    }
}
```

- [ ] **Step 3: Run Task 2 tests**

Run:

```bash
mvn -pl concurrency -Dtest=SingleProducerPublishTest,BackpressureTest test
```

Expected: PASS if Task 1 implementation already included the publish and backpressure behavior shown there. If a test fails, adjust only `SingleProducerSequencer` and `RingBuffer` to match the tested semantics.

- [ ] **Step 4: Run construction and publishing tests together**

Run:

```bash
mvn -pl concurrency -Dtest=RingBufferConstructionTest,SingleProducerPublishTest,BackpressureTest test
```

Expected: PASS.

- [ ] **Step 5: Commit Task 2**

Run:

```bash
git add concurrency/src/main/java/yier/bubu/concurrency/ringbuffer concurrency/src/test/java/yier/bubu/concurrency/ringbuffer/SingleProducerPublishTest.java concurrency/src/test/java/yier/bubu/concurrency/ringbuffer/BackpressureTest.java
git commit -m "feat: support single producer publishing"
```

## Task 3: Wait Strategies and Batch Event Processor

**Files:**
- Create: `concurrency/src/test/java/yier/bubu/concurrency/ringbuffer/WaitStrategyAndProcessorTest.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/YieldingWaitStrategy.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/BusySpinWaitStrategy.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/SleepingWaitStrategy.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/BatchEventProcessor.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/LoggingExceptionHandler.java`
- Modify: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/ProcessingSequenceBarrier.java`

- [ ] **Step 1: Write processor and wait-strategy tests**

Create `WaitStrategyAndProcessorTest.java`:

```java
package yier.bubu.concurrency.ringbuffer;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class WaitStrategyAndProcessorTest {
    @Test
    public void batchEventProcessor_shouldBroadcastEventsToMultipleConsumers() throws Exception {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 8, new BlockingWaitStrategy());

        RecordingHandler firstHandler = new RecordingHandler(3);
        RecordingHandler secondHandler = new RecordingHandler(3);

        BatchEventProcessor<TestEvent> first = new BatchEventProcessor<TestEvent>(
                ringBuffer,
                ringBuffer.newBarrier(),
                firstHandler,
                new LoggingExceptionHandler<TestEvent>());
        BatchEventProcessor<TestEvent> second = new BatchEventProcessor<TestEvent>(
                ringBuffer,
                ringBuffer.newBarrier(),
                secondHandler,
                new LoggingExceptionHandler<TestEvent>());

        ringBuffer.addGatingSequences(first.getSequence(), second.getSequence());

        first.start();
        second.start();
        try {
            for (long value = 1L; value <= 3L; value++) {
                ringBuffer.publish(valueTranslator(value));
            }

            Assert.assertTrue(firstHandler.await(1L, TimeUnit.SECONDS));
            Assert.assertTrue(secondHandler.await(1L, TimeUnit.SECONDS));
            Assert.assertEquals(asList(1L, 2L, 3L), firstHandler.values());
            Assert.assertEquals(asList(1L, 2L, 3L), secondHandler.values());
        } finally {
            first.halt();
            second.halt();
        }
    }

    @Test
    public void waitStrategies_shouldBeConstructibleAndUsableWithBarrier() throws Exception {
        assertStrategyCanObservePublishedEvent(new BlockingWaitStrategy());
        assertStrategyCanObservePublishedEvent(new YieldingWaitStrategy());
        assertStrategyCanObservePublishedEvent(new BusySpinWaitStrategy());
        assertStrategyCanObservePublishedEvent(new SleepingWaitStrategy());
    }

    private static void assertStrategyCanObservePublishedEvent(WaitStrategy waitStrategy) throws Exception {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 4, waitStrategy);
        SequenceBarrier barrier = ringBuffer.newBarrier();
        ringBuffer.publish(valueTranslator(99L));
        Assert.assertEquals(0L, barrier.waitFor(0L));
    }

    private static EventTranslator<TestEvent> valueTranslator(final long value) {
        return new EventTranslator<TestEvent>() {
            @Override
            public void translateTo(TestEvent event, long sequence) {
                event.value = value;
            }
        };
    }

    private static List<Long> asList(Long first, Long second, Long third) {
        List<Long> values = new ArrayList<Long>();
        values.add(first);
        values.add(second);
        values.add(third);
        return values;
    }

    private static final class RecordingHandler implements EventHandler<TestEvent> {
        private final CountDownLatch latch;
        private final List<Long> values = Collections.synchronizedList(new ArrayList<Long>());

        private RecordingHandler(int expectedEvents) {
            this.latch = new CountDownLatch(expectedEvents);
        }

        @Override
        public void onEvent(TestEvent event, long sequence) {
            values.add(event.value);
            latch.countDown();
        }

        private boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        private List<Long> values() {
            synchronized (values) {
                return new ArrayList<Long>(values);
            }
        }
    }

    private static final class TestEvent {
        private long value;
    }

    private static final class TestEventFactory implements EventFactory<TestEvent> {
        @Override
        public TestEvent newInstance() {
            return new TestEvent();
        }
    }
}
```

- [ ] **Step 2: Run failing processor tests**

Run:

```bash
mvn -pl concurrency -Dtest=WaitStrategyAndProcessorTest test
```

Expected: FAIL because `BatchEventProcessor`, `YieldingWaitStrategy`, `BusySpinWaitStrategy`, `SleepingWaitStrategy`, and `LoggingExceptionHandler` do not exist.

- [ ] **Step 3: Add the remaining wait strategies**

Create:

```java
// YieldingWaitStrategy.java
package yier.bubu.concurrency.ringbuffer;

public final class YieldingWaitStrategy implements WaitStrategy {
    private static final int SPIN_TRIES = 100;

    @Override
    public long waitFor(long sequence,
                        Sequence cursorSequence,
                        Sequence dependentSequence,
                        SequenceBarrier barrier) throws AlertException {
        long availableSequence;
        int counter = SPIN_TRIES;
        while ((availableSequence = dependentSequence.get()) < sequence) {
            barrier.checkAlert();
            if (counter == 0) {
                Thread.yield();
            } else {
                counter--;
            }
        }
        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking() {
    }
}
```

```java
// BusySpinWaitStrategy.java
package yier.bubu.concurrency.ringbuffer;

public final class BusySpinWaitStrategy implements WaitStrategy {
    @Override
    public long waitFor(long sequence,
                        Sequence cursorSequence,
                        Sequence dependentSequence,
                        SequenceBarrier barrier) throws AlertException {
        long availableSequence;
        while ((availableSequence = dependentSequence.get()) < sequence) {
            barrier.checkAlert();
        }
        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking() {
    }
}
```

```java
// SleepingWaitStrategy.java
package yier.bubu.concurrency.ringbuffer;

import java.util.concurrent.locks.LockSupport;

public final class SleepingWaitStrategy implements WaitStrategy {
    private static final int SPIN_TRIES = 100;
    private static final int YIELD_TRIES = 100;

    @Override
    public long waitFor(long sequence,
                        Sequence cursorSequence,
                        Sequence dependentSequence,
                        SequenceBarrier barrier) throws AlertException {
        long availableSequence;
        int counter = SPIN_TRIES + YIELD_TRIES;
        while ((availableSequence = dependentSequence.get()) < sequence) {
            barrier.checkAlert();
            if (counter > YIELD_TRIES) {
                counter--;
            } else if (counter > 0) {
                counter--;
                Thread.yield();
            } else {
                LockSupport.parkNanos(1L);
            }
        }
        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking() {
    }
}
```

- [ ] **Step 4: Add default exception handler**

Create:

```java
// LoggingExceptionHandler.java
package yier.bubu.concurrency.ringbuffer;

public final class LoggingExceptionHandler<T> implements ExceptionHandler<T> {
    @Override
    public void handleEventException(Throwable exception, long sequence, T event) {
        System.err.println("ring buffer event handler failed at sequence " + sequence + ": "
                + exception.getMessage());
    }
}
```

- [ ] **Step 5: Add batch event processor**

Create:

```java
// BatchEventProcessor.java
package yier.bubu.concurrency.ringbuffer;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BatchEventProcessor<T> implements Runnable {
    private final RingBuffer<T> ringBuffer;
    private final SequenceBarrier sequenceBarrier;
    private final EventHandler<T> eventHandler;
    private final ExceptionHandler<T> exceptionHandler;
    private final Sequence sequence = new Sequence(-1L);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread thread;

    public BatchEventProcessor(RingBuffer<T> ringBuffer,
                               SequenceBarrier sequenceBarrier,
                               EventHandler<T> eventHandler,
                               ExceptionHandler<T> exceptionHandler) {
        this.ringBuffer = Objects.requireNonNull(ringBuffer, "ringBuffer");
        this.sequenceBarrier = Objects.requireNonNull(sequenceBarrier, "sequenceBarrier");
        this.eventHandler = Objects.requireNonNull(eventHandler, "eventHandler");
        this.exceptionHandler = Objects.requireNonNull(exceptionHandler, "exceptionHandler");
    }

    public Sequence getSequence() {
        return sequence;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("processor is already running");
        }
        sequenceBarrier.clearAlert();
        Thread newThread = new Thread(this, "ring-buffer-batch-event-processor");
        thread = newThread;
        newThread.start();
    }

    public void halt() {
        running.set(false);
        sequenceBarrier.alert();
        Thread currentThread = thread;
        if (currentThread != null) {
            currentThread.interrupt();
        }
    }

    @Override
    public void run() {
        long nextSequence = sequence.get() + 1L;
        try {
            while (running.get()) {
                long availableSequence = sequenceBarrier.waitFor(nextSequence);
                while (nextSequence <= availableSequence && running.get()) {
                    T event = ringBuffer.get(nextSequence);
                    boolean advanceSequence = true;
                    try {
                        eventHandler.onEvent(event, nextSequence);
                    } catch (Throwable handlerFailure) {
                        try {
                            exceptionHandler.handleEventException(handlerFailure, nextSequence, event);
                        } catch (Throwable fatalFailure) {
                            advanceSequence = false;
                            running.set(false);
                            throw fatalFailure;
                        }
                    } finally {
                        if (advanceSequence) {
                            sequence.set(nextSequence);
                        }
                    }
                    nextSequence++;
                }
            }
        } catch (AlertException alert) {
            if (running.get()) {
                exceptionHandler.handleEventException(alert, nextSequence, null);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable fatalFailure) {
            exceptionHandler.handleEventException(fatalFailure, nextSequence, null);
        } finally {
            running.set(false);
        }
    }
}
```

- [ ] **Step 6: Run processor tests**

Run:

```bash
mvn -pl concurrency -Dtest=WaitStrategyAndProcessorTest test
```

Expected: PASS.

- [ ] **Step 7: Run all ringbuffer tests so far**

Run:

```bash
mvn -pl concurrency -Dtest=RingBufferConstructionTest,SingleProducerPublishTest,BackpressureTest,WaitStrategyAndProcessorTest test
```

Expected: PASS.

- [ ] **Step 8: Commit Task 3**

Run:

```bash
git add concurrency/src/main/java/yier/bubu/concurrency/ringbuffer concurrency/src/test/java/yier/bubu/concurrency/ringbuffer/WaitStrategyAndProcessorTest.java
git commit -m "feat: add ring buffer event processors"
```

## Task 4: Multi-Producer Sequencer

**Files:**
- Create: `concurrency/src/test/java/yier/bubu/concurrency/ringbuffer/MultiProducerSequencerTest.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/MultiProducerSequencer.java`
- Modify: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/RingBuffer.java`

- [ ] **Step 1: Write multi-producer sequencing tests**

Create `MultiProducerSequencerTest.java`:

```java
package yier.bubu.concurrency.ringbuffer;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MultiProducerSequencerTest {
    @Test
    public void publish_shouldNotAdvanceCursorPastGap() {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createMultiProducer(new TestEventFactory(), 4, new BlockingWaitStrategy());

        long first = ringBuffer.next();
        long second = ringBuffer.next();

        ringBuffer.get(second).value = 2L;
        ringBuffer.publish(second);

        Assert.assertEquals(-1L, ringBuffer.getCursor());

        ringBuffer.get(first).value = 1L;
        ringBuffer.publish(first);

        Assert.assertEquals(1L, ringBuffer.getCursor());
    }

    @Test
    public void createMultiProducer_shouldPublishFromMultipleThreads() throws Exception {
        final RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createMultiProducer(new TestEventFactory(), 64, new BlockingWaitStrategy());

        final AtomicInteger published = new AtomicInteger(0);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(2);

        Runnable publisher = new Runnable() {
            @Override
            public void run() {
                try {
                    start.await();
                    for (int i = 0; i < 25; i++) {
                        ringBuffer.publish(new EventTranslator<TestEvent>() {
                            @Override
                            public void translateTo(TestEvent event, long sequence) {
                                event.value = sequence;
                                published.incrementAndGet();
                            }
                        });
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }
        };

        new Thread(publisher, "publisher-1").start();
        new Thread(publisher, "publisher-2").start();
        start.countDown();

        Assert.assertTrue(done.await(1L, TimeUnit.SECONDS));
        Assert.assertEquals(50, published.get());
        Assert.assertEquals(49L, ringBuffer.getCursor());
    }

    private static final class TestEvent {
        private long value;
    }

    private static final class TestEventFactory implements EventFactory<TestEvent> {
        @Override
        public TestEvent newInstance() {
            return new TestEvent();
        }
    }
}
```

- [ ] **Step 2: Run failing multi-producer tests**

Run:

```bash
mvn -pl concurrency -Dtest=MultiProducerSequencerTest test
```

Expected: FAIL because `RingBuffer.createMultiProducer(...)` and `MultiProducerSequencer` do not exist.

- [ ] **Step 3: Add multi-producer factory**

Modify `RingBuffer.java` by adding this factory next to `createSingleProducer(...)`:

```java
public static <T> RingBuffer<T> createMultiProducer(
        EventFactory<T> eventFactory,
        int bufferSize,
        WaitStrategy waitStrategy) {
    return new RingBuffer<T>(
            Objects.requireNonNull(eventFactory, "eventFactory"),
            new MultiProducerSequencer(bufferSize, waitStrategy));
}
```

- [ ] **Step 4: Add multi-producer sequencer**

Create:

```java
// MultiProducerSequencer.java
package yier.bubu.concurrency.ringbuffer;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public final class MultiProducerSequencer extends AbstractSequencer {
    private final AtomicLong nextValue = new AtomicLong(-1L);
    private final int[] availableBuffer;
    private final int indexMask;
    private final int indexShift;
    private volatile long cachedGatingSequence = -1L;

    public MultiProducerSequencer(int bufferSize, WaitStrategy waitStrategy) {
        super(bufferSize, waitStrategy);
        this.availableBuffer = new int[bufferSize];
        Arrays.fill(availableBuffer, -1);
        this.indexMask = bufferSize - 1;
        this.indexShift = Integer.numberOfTrailingZeros(bufferSize);
    }

    @Override
    public long next() {
        while (true) {
            long current = nextValue.get();
            long next = current + 1L;
            if (hasAvailableCapacity(current, next)) {
                if (nextValue.compareAndSet(current, next)) {
                    return next;
                }
            } else {
                LockSupport.parkNanos(1L);
            }
        }
    }

    @Override
    public long tryNext() throws InsufficientCapacityException {
        while (true) {
            long current = nextValue.get();
            long next = current + 1L;
            if (!hasAvailableCapacity(current, next)) {
                throw InsufficientCapacityException.INSTANCE;
            }
            if (nextValue.compareAndSet(current, next)) {
                return next;
            }
        }
    }

    @Override
    public long next(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            long current = nextValue.get();
            long next = current + 1L;
            if (hasAvailableCapacity(current, next)) {
                if (nextValue.compareAndSet(current, next)) {
                    return next;
                }
            } else {
                if (System.nanoTime() >= deadlineNanos) {
                    throw new TimeoutException("timed out waiting for ring buffer capacity");
                }
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                LockSupport.parkNanos(1L);
            }
        }
    }

    @Override
    public void publish(long sequence) {
        setAvailable(sequence);
        advanceCursor();
        waitStrategy.signalAllWhenBlocking();
    }

    @Override
    public boolean isAvailable(long sequence) {
        int index = calculateIndex(sequence);
        int flag = calculateAvailabilityFlag(sequence);
        return availableBuffer[index] == flag;
    }

    @Override
    public long getHighestPublishedSequence(long lowerBound, long availableSequence) {
        for (long sequence = lowerBound; sequence <= availableSequence; sequence++) {
            if (!isAvailable(sequence)) {
                return sequence - 1L;
            }
        }
        return availableSequence;
    }

    private boolean hasAvailableCapacity(long current, long next) {
        long wrapPoint = next - bufferSize;
        long cached = cachedGatingSequence;
        if (wrapPoint > cached || cached > current) {
            long minSequence = getMinimumGatingSequence(current);
            cachedGatingSequence = minSequence;
            return wrapPoint <= minSequence;
        }
        return true;
    }

    private void setAvailable(long sequence) {
        availableBuffer[calculateIndex(sequence)] = calculateAvailabilityFlag(sequence);
    }

    private int calculateIndex(long sequence) {
        return (int) sequence & indexMask;
    }

    private int calculateAvailabilityFlag(long sequence) {
        return (int) (sequence >>> indexShift);
    }

    private void advanceCursor() {
        synchronized (this) {
            long currentCursor = cursor.get();
            long highestClaimed = nextValue.get();
            long next = currentCursor + 1L;
            while (next <= highestClaimed && isAvailable(next)) {
                next++;
            }
            cursor.set(next - 1L);
        }
    }
}
```

- [ ] **Step 5: Run multi-producer tests**

Run:

```bash
mvn -pl concurrency -Dtest=MultiProducerSequencerTest test
```

Expected: PASS.

- [ ] **Step 6: Run all ringbuffer tests so far**

Run:

```bash
mvn -pl concurrency -Dtest=RingBufferConstructionTest,SingleProducerPublishTest,BackpressureTest,WaitStrategyAndProcessorTest,MultiProducerSequencerTest test
```

Expected: PASS.

- [ ] **Step 7: Commit Task 4**

Run:

```bash
git add concurrency/src/main/java/yier/bubu/concurrency/ringbuffer concurrency/src/test/java/yier/bubu/concurrency/ringbuffer/MultiProducerSequencerTest.java
git commit -m "feat: add multi producer sequencing"
```

## Task 5: Fatal Exception Handling and Processor Halt Semantics

**Files:**
- Create: `concurrency/src/test/java/yier/bubu/concurrency/ringbuffer/ExceptionHandlingTest.java`
- Create: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/FatalExceptionHandler.java`
- Modify: `concurrency/src/main/java/yier/bubu/concurrency/ringbuffer/BatchEventProcessor.java`

- [ ] **Step 1: Write exception handling tests**

Create `ExceptionHandlingTest.java`:

```java
package yier.bubu.concurrency.ringbuffer;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class ExceptionHandlingTest {
    @Test
    public void loggingExceptionHandler_shouldLetProcessorAdvanceAfterHandlerFailure() throws Exception {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 4, new BlockingWaitStrategy());
        final CountDownLatch secondEventHandled = new CountDownLatch(1);

        BatchEventProcessor<TestEvent> processor = new BatchEventProcessor<TestEvent>(
                ringBuffer,
                ringBuffer.newBarrier(),
                new EventHandler<TestEvent>() {
                    @Override
                    public void onEvent(TestEvent event, long sequence) {
                        if (sequence == 0L) {
                            throw new IllegalStateException("first event failed");
                        }
                        secondEventHandled.countDown();
                    }
                },
                new LoggingExceptionHandler<TestEvent>());

        ringBuffer.addGatingSequences(processor.getSequence());
        processor.start();
        try {
            ringBuffer.publish(valueTranslator(1L));
            ringBuffer.publish(valueTranslator(2L));

            Assert.assertTrue(secondEventHandled.await(1L, TimeUnit.SECONDS));
            Assert.assertEquals(1L, processor.getSequence().get());
        } finally {
            processor.halt();
        }
    }

    @Test
    public void fatalExceptionHandler_shouldStopProcessorWithoutAdvancingFailedSequence() throws Exception {
        RingBuffer<TestEvent> ringBuffer =
                RingBuffer.createSingleProducer(new TestEventFactory(), 4, new BlockingWaitStrategy());
        final AtomicLong failedAt = new AtomicLong(-1L);

        BatchEventProcessor<TestEvent> processor = new BatchEventProcessor<TestEvent>(
                ringBuffer,
                ringBuffer.newBarrier(),
                new EventHandler<TestEvent>() {
                    @Override
                    public void onEvent(TestEvent event, long sequence) {
                        failedAt.set(sequence);
                        throw new IllegalStateException("fatal event failed");
                    }
                },
                new FatalExceptionHandler<TestEvent>());

        ringBuffer.addGatingSequences(processor.getSequence());
        processor.start();
        ringBuffer.publish(valueTranslator(1L));

        waitUntilStopped(processor);

        Assert.assertEquals(0L, failedAt.get());
        Assert.assertFalse(processor.isRunning());
        Assert.assertEquals(-1L, processor.getSequence().get());
    }

    private static void waitUntilStopped(BatchEventProcessor<TestEvent> processor) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
        while (processor.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
    }

    private static EventTranslator<TestEvent> valueTranslator(final long value) {
        return new EventTranslator<TestEvent>() {
            @Override
            public void translateTo(TestEvent event, long sequence) {
                event.value = value;
            }
        };
    }

    private static final class TestEvent {
        private long value;
    }

    private static final class TestEventFactory implements EventFactory<TestEvent> {
        @Override
        public TestEvent newInstance() {
            return new TestEvent();
        }
    }
}
```

- [ ] **Step 2: Run failing exception tests**

Run:

```bash
mvn -pl concurrency -Dtest=ExceptionHandlingTest test
```

Expected: FAIL because `FatalExceptionHandler` does not exist.

- [ ] **Step 3: Add fatal exception handler**

Create:

```java
// FatalExceptionHandler.java
package yier.bubu.concurrency.ringbuffer;

public final class FatalExceptionHandler<T> implements ExceptionHandler<T> {
    @Override
    public void handleEventException(Throwable exception, long sequence, T event) {
        throw new IllegalStateException("fatal ring buffer event handler failure at sequence " + sequence, exception);
    }
}
```

- [ ] **Step 4: Run exception tests**

Run:

```bash
mvn -pl concurrency -Dtest=ExceptionHandlingTest test
```

Expected: PASS. If the fatal test fails because the processor advances the failed sequence, change `BatchEventProcessor` so a runtime exception thrown by `ExceptionHandler` sets `advanceSequence = false` before leaving the event loop.

- [ ] **Step 5: Run all ringbuffer tests**

Run:

```bash
mvn -pl concurrency -Dtest=RingBufferConstructionTest,SingleProducerPublishTest,BackpressureTest,WaitStrategyAndProcessorTest,MultiProducerSequencerTest,ExceptionHandlingTest test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 5**

Run:

```bash
git add concurrency/src/main/java/yier/bubu/concurrency/ringbuffer concurrency/src/test/java/yier/bubu/concurrency/ringbuffer/ExceptionHandlingTest.java
git commit -m "feat: add ring buffer exception handlers"
```

## Task 6: Documentation

**Files:**
- Create: `concurrency/docs/ring-buffer-publisher.md`
- Do not modify `mkdocs/mkdocs.yml` in this plan because the current workspace
  already has unrelated uncommitted changes in that file.

- [ ] **Step 1: Create source documentation**

Create `concurrency/docs/ring-buffer-publisher.md`:

````markdown
# Ring Buffer Publisher

This page explains the in-process ring-buffer publisher implemented in
`yier.bubu.concurrency.ringbuffer`.

## 1. What It Is

The framework is a JVM-local publisher pipeline. Producers claim a numeric
sequence, write data into a preallocated slot, and publish that sequence.
Consumers wait for published sequences and process contiguous batches.

It is not a durable message broker. It does not provide cross-process delivery,
message replay, persistence, transactions, or acknowledgement protocols.

## 2. Ring Buffer Layout

The buffer size must be a power of two. A sequence maps to an array slot with:

```java
index = sequence & (bufferSize - 1)
```

This replaces modulo with a bit mask and makes wrap-around cheap.

## 3. Preallocated Slots

`EventFactory<T>` creates all event objects when the ring buffer is constructed.
Publishing reuses those objects through `EventTranslator<T>`, which mutates the
claimed slot:

```java
ringBuffer.publish(new EventTranslator<OrderEvent>() {
    @Override
    public void translateTo(OrderEvent event, long sequence) {
        event.setOrderId("A-1001");
        event.setAmount(99L);
    }
});
```

The publish path does not allocate a new event object per message.

## 4. Cursor and Gating Sequences

The producer cursor is the highest sequence that is visible to consumers.
Each consumer owns a `Sequence`. Those consumer sequences are registered as
gating sequences on the ring buffer.

Before a producer wraps around and reuses a slot, it checks the minimum gating
sequence. The slowest consumer controls when old slots can be overwritten.

## 5. Single Producer and Multi Producer

`SingleProducerSequencer` keeps the next claim in a normal field because only
one thread publishes.

`MultiProducerSequencer` uses CAS to claim sequences. It also tracks slot
availability separately so consumers do not pass gaps. If producer A claims
sequence 10 and producer B claims sequence 11, sequence 11 can become available
before 10. The visible cursor advances only when the published range is
contiguous.

## 6. Wait Strategies

`BlockingWaitStrategy` is the default for examples and tests. It blocks consumer
threads when no sequence is available.

`YieldingWaitStrategy`, `BusySpinWaitStrategy`, and `SleepingWaitStrategy`
trade CPU usage for lower wake-up latency.

## 7. Broadcast Consumption

The first implementation supports broadcast consumers. Every
`BatchEventProcessor<T>` receives every published event and maintains its own
consumer sequence.

Competitive consumption can be built as a separate worker-pool layer on top of
the same ring-buffer core.

## 8. Failure Handling

`LoggingExceptionHandler<T>` records a consumer failure and lets the processor
advance. This avoids permanently blocking producers because of one failed
handler call.

`FatalExceptionHandler<T>` throws from the exception handler and stops the
processor without advancing the failed sequence.
````

- [ ] **Step 2: Inspect MkDocs navigation without editing it**

Run:

```bash
rg -n "concurrency/docs|thread-states|juc|volatile|ring" mkdocs/mkdocs.yml
```

Expected: capture whether the page needs a navigation follow-up. Do not edit or
stage `mkdocs/mkdocs.yml` as part of this feature plan.

- [ ] **Step 3: Run documentation-relevant tests**

Run:

```bash
mvn -pl concurrency test
```

Expected: PASS.

- [ ] **Step 4: Commit Task 6**

Run:

```bash
git add concurrency/docs/ring-buffer-publisher.md
git commit -m "docs: explain ring buffer publisher"
```

## Task 7: Full Verification

**Files:**
- No file creation expected.
- Modify only files required to fix failures found by verification.

- [ ] **Step 1: Run concurrency module tests**

Run:

```bash
mvn -pl concurrency test
```

Expected: PASS.

- [ ] **Step 2: Run full Maven test suite**

Run:

```bash
mvn test
```

Expected: PASS. If unrelated pre-existing failures appear outside the ring-buffer changes, capture the failing module, test class, and failure message before deciding whether to fix or report them.

- [ ] **Step 3: Inspect git status**

Run:

```bash
git status --short
```

Expected: only intentional ring-buffer changes remain. Existing user changes outside this feature must not be reverted.

- [ ] **Step 4: Commit verification fixes if any were made**

If verification required code fixes, run:

```bash
git add concurrency/src/main/java/yier/bubu/concurrency/ringbuffer concurrency/src/test/java/yier/bubu/concurrency/ringbuffer concurrency/docs/ring-buffer-publisher.md
git commit -m "fix: stabilize ring buffer publisher"
```

If no files changed during verification, do not create an empty commit.

## Self-Review Checklist

- Spec coverage:
  - Broadcast core is covered by Tasks 1 through 3.
  - Single-producer publishing is covered by Task 2.
  - Multi-producer publishing and gap handling are covered by Task 4.
  - Backpressure and timeout publish are covered by Task 2.
  - Wait strategies are covered by Task 3.
  - Exception handling is covered by Task 5.
  - Documentation is covered by Task 6.
  - Full verification is covered by Task 7.

- Placeholder scan:
  - The plan uses concrete file paths, commands, expected results, and code snippets.
  - There are no placeholder sections or unspecified implementation tasks.

- Type consistency:
  - `RingBuffer<T>` factory methods match the spec.
  - `EventTranslator<T>`, `EventHandler<T>`, and `ExceptionHandler<T>` signatures match all tests.
  - `tryNext()` consistently throws `InsufficientCapacityException`.
  - `SequenceBarrier.waitFor(...)` consistently throws `AlertException` and `InterruptedException`.
