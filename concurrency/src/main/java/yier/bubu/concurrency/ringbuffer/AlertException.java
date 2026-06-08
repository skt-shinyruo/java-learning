package yier.bubu.concurrency.ringbuffer;

public final class AlertException extends Exception {
    public static final AlertException INSTANCE = new AlertException();

    private AlertException() {
        super("sequence barrier alerted", null, false, false);
    }
}
