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
        for (int index = 0; index < entries.length; index++) {
            entries[index] = Objects.requireNonNull(eventFactory.newInstance(), "event");
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

    public static <T> RingBuffer<T> createMultiProducer(
            EventFactory<T> eventFactory,
            int bufferSize,
            WaitStrategy waitStrategy) {
        return new RingBuffer<T>(
                Objects.requireNonNull(eventFactory, "eventFactory"),
                new MultiProducerSequencer(bufferSize, waitStrategy));
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
