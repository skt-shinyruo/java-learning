package yier.bubu.concurrency.ringbuffer;

public interface SequenceBarrier {
    long waitFor(long sequence) throws AlertException, InterruptedException;

    long getCursor();

    void alert();

    void clearAlert();

    boolean isAlerted();

    void checkAlert() throws AlertException;
}
