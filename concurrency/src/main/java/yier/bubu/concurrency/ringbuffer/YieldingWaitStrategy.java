package yier.bubu.concurrency.ringbuffer;

public final class YieldingWaitStrategy implements WaitStrategy {
    private static final int SPIN_TRIES = 100;

    @Override
    public long waitFor(long sequence,
                        Sequence cursorSequence,
                        Sequence dependentSequence,
                        SequenceBarrier barrier) throws AlertException {
        int counter = SPIN_TRIES;
        long availableSequence;
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
