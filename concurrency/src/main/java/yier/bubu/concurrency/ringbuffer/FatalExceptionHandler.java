package yier.bubu.concurrency.ringbuffer;

public final class FatalExceptionHandler<T> implements ExceptionHandler<T> {
    @Override
    public void handleEventException(Throwable exception, long sequence, T event) {
        throw new IllegalStateException("Exception processing sequence " + sequence, exception);
    }
}
