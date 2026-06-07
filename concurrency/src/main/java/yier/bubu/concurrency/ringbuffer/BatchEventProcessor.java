package yier.bubu.concurrency.ringbuffer;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BatchEventProcessor<T> implements Runnable {
    private final RingBuffer<T> ringBuffer;
    private final SequenceBarrier sequenceBarrier;
    private final EventHandler<T> eventHandler;
    private final ExceptionHandler<? super T> exceptionHandler;
    private final Sequence sequence = new Sequence(-1L);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Thread thread;

    public BatchEventProcessor(RingBuffer<T> ringBuffer,
                               SequenceBarrier sequenceBarrier,
                               EventHandler<T> eventHandler,
                               ExceptionHandler<? super T> exceptionHandler) {
        this.ringBuffer = Objects.requireNonNull(ringBuffer, "ringBuffer");
        this.sequenceBarrier = Objects.requireNonNull(sequenceBarrier, "sequenceBarrier");
        this.eventHandler = Objects.requireNonNull(eventHandler, "eventHandler");
        this.exceptionHandler = Objects.requireNonNull(exceptionHandler, "exceptionHandler");
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("BatchEventProcessor is already running");
        }
        sequenceBarrier.clearAlert();
        Thread processorThread = new Thread(this, "batch-event-processor");
        thread = processorThread;
        processorThread.start();
    }

    public void halt() {
        running.set(false);
        sequenceBarrier.alert();
    }

    public boolean isRunning() {
        return running.get();
    }

    public Sequence getSequence() {
        return sequence;
    }

    @Override
    public void run() {
        long nextSequence = sequence.get() + 1L;
        try {
            while (running.get()) {
                long availableSequence = sequenceBarrier.waitFor(nextSequence);
                while (nextSequence <= availableSequence) {
                    T event = ringBuffer.get(nextSequence);
                    try {
                        eventHandler.onEvent(event, nextSequence);
                        sequence.set(nextSequence);
                        nextSequence++;
                    } catch (Throwable exception) {
                        exceptionHandler.handleEventException(exception, nextSequence, event);
                        sequence.set(nextSequence);
                        nextSequence++;
                    }
                }
            }
        } catch (AlertException exception) {
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            thread = null;
            running.set(false);
        }
    }
}
