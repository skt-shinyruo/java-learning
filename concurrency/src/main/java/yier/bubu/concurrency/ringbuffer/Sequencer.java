package yier.bubu.concurrency.ringbuffer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public interface Sequencer {
    int getBufferSize();

    long getCursor();

    Sequence getCursorSequence();

    long next();

    long tryNext() throws InsufficientCapacityException;

    long next(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException;

    void publish(long sequence);

    boolean isAvailable(long sequence);

    long getHighestPublishedSequence(long lowerBound, long availableSequence);

    void addGatingSequences(Sequence... sequences);

    boolean removeGatingSequence(Sequence sequence);

    SequenceBarrier newBarrier();
}
