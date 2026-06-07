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
            long minimumSequence = getMinimumGatingSequence(nextValue);
            cachedGatingSequence = minimumSequence;
            return wrapPoint <= minimumSequence;
        }
        return true;
    }
}
