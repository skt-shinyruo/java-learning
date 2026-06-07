package yier.bubu.concurrency.ringbuffer;

public final class InsufficientCapacityException extends Exception {
    public static final InsufficientCapacityException INSTANCE = new InsufficientCapacityException();

    private InsufficientCapacityException() {
        super("insufficient ring buffer capacity", null, false, false);
    }
}
