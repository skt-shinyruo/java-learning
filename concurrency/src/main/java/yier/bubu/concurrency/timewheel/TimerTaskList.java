package yier.bubu.concurrency.timewheel;

import java.util.Objects;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * One bucket (a list of entries) with a single expiration timestamp.
 *
 * <p>Expiration is set when the first entry is inserted for a given tick and reset to -1 after flush.
 */
final class TimerTaskList implements Delayed {
    private static final Runnable NOOP = () -> {
    };

    private final LongSupplier nanoTimeSupplier;
    private final TimerTaskEntry root;

    private long expirationNanos = -1L;

    TimerTaskList(LongSupplier nanoTimeSupplier) {
        this.nanoTimeSupplier = Objects.requireNonNull(nanoTimeSupplier, "nanoTimeSupplier");
        this.root = new TimerTaskEntry(NOOP, -1L);
        this.root.next = root;
        this.root.prev = root;
    }

    long getExpirationNanos() {
        return expirationNanos;
    }

    boolean setExpirationNanos(long expirationNanos) {
        if (this.expirationNanos == expirationNanos) {
            return false;
        }
        this.expirationNanos = expirationNanos;
        return true;
    }

    void add(TimerTaskEntry entry) {
        // Caller holds scheduler lock.
        if (entry.list != null) {
            entry.list.remove(entry);
        }

        entry.list = this;
        TimerTaskEntry tail = root.prev;
        entry.next = root;
        entry.prev = tail;
        tail.next = entry;
        root.prev = entry;
    }

    void remove(TimerTaskEntry entry) {
        // Caller holds scheduler lock.
        if (entry.list != this) {
            return;
        }
        entry.next.prev = entry.prev;
        entry.prev.next = entry.next;
        entry.next = null;
        entry.prev = null;
        entry.list = null;
    }

    void flush(Consumer<TimerTaskEntry> consumer) {
        // Caller holds scheduler lock.
        TimerTaskEntry cur = root.next;
        while (cur != root) {
            TimerTaskEntry next = cur.next;
            remove(cur);
            consumer.accept(cur);
            cur = next;
        }
        expirationNanos = -1L;
    }

    @Override
    public long getDelay(TimeUnit unit) {
        long delay = expirationNanos - nanoTimeSupplier.getAsLong();
        return unit.convert(delay, TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
        if (o == this) {
            return 0;
        }
        TimerTaskList other = (TimerTaskList) o;
        long diff = expirationNanos - other.expirationNanos;
        return diff < 0 ? -1 : (diff > 0 ? 1 : 0);
    }
}

