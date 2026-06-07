package yier.bubu.concurrency.ringbuffer;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public final class MultiProducerSequencer extends AbstractSequencer {
    private static final int INITIAL_AVAILABLE = -1;

    private final AtomicLong nextValue = new AtomicLong(-1L);
    private final AtomicLong cachedGatingSequence = new AtomicLong(-1L);
    private final int[] availableBuffer;
    private final int indexMask;
    private final int indexShift;

    public MultiProducerSequencer(int bufferSize, WaitStrategy waitStrategy) {
        super(bufferSize, waitStrategy);
        this.availableBuffer = new int[bufferSize];
        Arrays.fill(availableBuffer, INITIAL_AVAILABLE);
        this.indexMask = bufferSize - 1;
        this.indexShift = Integer.numberOfTrailingZeros(bufferSize);
    }

    @Override
    public long next() {
        while (true) {
            long sequence = tryClaimNext();
            if (sequence >= 0L) {
                return sequence;
            }
            LockSupport.parkNanos(1L);
        }
    }

    @Override
    public long tryNext() throws InsufficientCapacityException {
        long sequence = tryClaimNext();
        if (sequence < 0L) {
            throw InsufficientCapacityException.INSTANCE;
        }
        return sequence;
    }

    @Override
    public long next(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            long sequence = tryClaimNext();
            if (sequence >= 0L) {
                return sequence;
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw new TimeoutException("timed out waiting for ring buffer capacity");
            }
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            LockSupport.parkNanos(1L);
        }
    }

    @Override
    public void publish(long sequence) {
        synchronized (this) {
            setAvailable(sequence);
            advanceCursor();
        }
        waitStrategy.signalAllWhenBlocking();
    }

    @Override
    public synchronized boolean isAvailable(long sequence) {
        return isAvailableUnsafe(sequence);
    }

    @Override
    public synchronized long getHighestPublishedSequence(long lowerBound, long availableSequence) {
        for (long sequence = lowerBound; sequence <= availableSequence; sequence++) {
            if (!isAvailableUnsafe(sequence)) {
                return sequence - 1L;
            }
        }
        return availableSequence;
    }

    private long tryClaimNext() {
        while (true) {
            long current = nextValue.get();
            long nextSequence = current + 1L;
            if (!hasAvailableCapacity(current, nextSequence)) {
                return -1L;
            }
            if (nextValue.compareAndSet(current, nextSequence)) {
                return nextSequence;
            }
        }
    }

    private boolean hasAvailableCapacity(long current, long nextSequence) {
        long wrapPoint = nextSequence - bufferSize;
        long cachedSequence = cachedGatingSequence.get();
        if (wrapPoint > cachedSequence || cachedSequence > current) {
            long minimumSequence = getMinimumGatingSequence(current);
            if (wrapPoint > minimumSequence) {
                return false;
            }
            cachedGatingSequence.set(minimumSequence);
        }
        return true;
    }

    private void setAvailable(long sequence) {
        availableBuffer[calculateIndex(sequence)] = calculateAvailabilityFlag(sequence);
    }

    private boolean isAvailableUnsafe(long sequence) {
        return availableBuffer[calculateIndex(sequence)] == calculateAvailabilityFlag(sequence);
    }

    private int calculateIndex(long sequence) {
        return (int) sequence & indexMask;
    }

    private int calculateAvailabilityFlag(long sequence) {
        return (int) (sequence >>> indexShift);
    }

    private void advanceCursor() {
        long nextSequence = cursor.get() + 1L;
        long claimedSequence = nextValue.get();
        while (nextSequence <= claimedSequence && isAvailableUnsafe(nextSequence)) {
            cursor.set(nextSequence);
            nextSequence++;
        }
    }
}
