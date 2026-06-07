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

    protected long getMinimumGatingSequence(long defaultValue) {
        return SequenceUtil.getMinimumSequence(gatingSequences, defaultValue);
    }
}
