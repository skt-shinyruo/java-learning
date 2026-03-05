package yier.bubu.concurrency.ratelimit;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Queue-based limiting (Bounded Queue):
 * Accept requests into a bounded in-memory queue; reject when the queue is full.
 * <p>
 * This does not "control QPS" by itself; it's usually combined with a worker that drains the queue
 * (or combined with leaky-bucket / concurrency limiting).
 */
public final class BoundedQueueLimiter {
    private final int capacity;
    private final Deque<Object> queue;

    public BoundedQueueLimiter(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.queue = new ArrayDeque<>(capacity);
    }

    public synchronized boolean tryEnqueue() {
        if (queue.size() >= capacity) {
            return false;
        }
        // 这里只需要“计数”，不关心元素内容，因此用占位对象即可。
        queue.addLast(Boolean.TRUE);
        return true;
    }

    public synchronized boolean tryDequeue() {
        if (queue.isEmpty()) {
            return false;
        }
        queue.removeFirst();
        return true;
    }

    public synchronized int getSize() {
        return queue.size();
    }

    public int getCapacity() {
        return capacity;
    }
}
