package yier.bubu.concurrency.ringbuffer;

public interface WaitStrategy {
    long waitFor(long sequence,
                 Sequence cursorSequence,
                 Sequence dependentSequence,
                 SequenceBarrier barrier) throws AlertException, InterruptedException;

    void signalAllWhenBlocking();
}
