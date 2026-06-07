package yier.bubu.concurrency.ringbuffer;

public final class LoggingExceptionHandler<T> implements ExceptionHandler<T> {
    @Override
    public void handleEventException(Throwable exception, long sequence, T event) {
        System.err.println("Exception processing sequence " + sequence + ": " + exception.getMessage());
        exception.printStackTrace(System.err);
    }
}
