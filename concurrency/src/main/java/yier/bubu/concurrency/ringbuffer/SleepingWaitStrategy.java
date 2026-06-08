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
        int counter = SPIN_TRIES + YIELD_TRIES;
        long availableSequence;
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
