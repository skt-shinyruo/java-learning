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
