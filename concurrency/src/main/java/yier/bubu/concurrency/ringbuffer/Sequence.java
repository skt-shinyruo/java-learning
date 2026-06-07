package yier.bubu.concurrency.ringbuffer;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;

public class Sequence {
    private static final AtomicLongFieldUpdater<Sequence> VALUE_UPDATER =
            AtomicLongFieldUpdater.newUpdater(Sequence.class, "value");

    @SuppressWarnings("unused")
    private long p1, p2, p3, p4, p5, p6, p7;
    private volatile long value;
    @SuppressWarnings("unused")
    private long p8, p9, p10, p11, p12, p13, p14;

    public Sequence() {
        this(-1L);
    }

    public Sequence(long initialValue) {
        this.value = initialValue;
    }

    public long get() {
        return value;
    }

    public void set(long value) {
        this.value = value;
    }

    public boolean compareAndSet(long expectedValue, long newValue) {
        return VALUE_UPDATER.compareAndSet(this, expectedValue, newValue);
    }
}
