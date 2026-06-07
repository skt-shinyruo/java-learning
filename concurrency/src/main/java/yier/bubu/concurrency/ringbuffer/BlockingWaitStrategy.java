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
