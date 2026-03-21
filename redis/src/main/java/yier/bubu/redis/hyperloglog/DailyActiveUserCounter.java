package yier.bubu.redis.hyperloglog;

import java.util.Objects;

public final class DailyActiveUserCounter {
    private final HyperLogLog<Long> sketch;

    public DailyActiveUserCounter(int precision) {
        this.sketch = new HyperLogLog<Long>(precision);
    }

    public void recordVisit(long userId) {
        sketch.add(userId);
    }

    public long estimateDailyActiveUsers() {
        return sketch.estimate();
    }

    public void merge(DailyActiveUserCounter other) {
        sketch.merge(Objects.requireNonNull(other, "other").sketch);
    }
}
