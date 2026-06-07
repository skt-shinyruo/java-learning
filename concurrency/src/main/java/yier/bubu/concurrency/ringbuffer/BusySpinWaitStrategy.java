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
