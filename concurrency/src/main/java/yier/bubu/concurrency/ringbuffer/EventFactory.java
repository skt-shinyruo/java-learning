package yier.bubu.concurrency.ringbuffer;

public interface EventFactory<T> {
    T newInstance();
}
