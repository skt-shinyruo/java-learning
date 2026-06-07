package yier.bubu.concurrency.ringbuffer;

public interface EventTranslator<T> {
    void translateTo(T event, long sequence);
}
