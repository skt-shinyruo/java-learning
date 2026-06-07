package yier.bubu.concurrency.ringbuffer;

public interface EventHandler<T> {
    void onEvent(T event, long sequence) throws Exception;
}
