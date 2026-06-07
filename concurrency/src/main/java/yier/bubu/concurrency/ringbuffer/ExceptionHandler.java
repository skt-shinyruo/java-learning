package yier.bubu.concurrency.ringbuffer;

public interface ExceptionHandler<T> {
    void handleEventException(Throwable exception, long sequence, T event);
}
