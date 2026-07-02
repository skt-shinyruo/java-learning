package yier.bubu.concurrency.timewheel;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 用于教学的单层时间轮实现。
 *
 * <p>这个类刻意省略任务取消、周期任务、overflow 层级、时间漂移补偿和外部执行器集成。
 * 任务直接在时间轮 worker 线程中执行，所以耗时任务会拖慢后续 tick。
 */
public final class SimpleTimingWheel implements AutoCloseable {
    private final long tickMillis;
    private final long tickNanos;
    private final int wheelSize;
    private final List<TimerTask>[] buckets;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 最近一次已经扫描过的槽位。下一次 worker tick 会扫描 {@code currentSlot + 1}。
     */
    private int currentSlot;

    private volatile boolean running;
    private boolean started;
    private Thread workerThread;

    /**
     * 创建一个单层时间轮。
     *
     * @param tickMillis 每个 tick 的毫秒数，必须大于 0
     * @param wheelSize 槽位数量，必须大于 0
     */
    @SuppressWarnings("unchecked")
    public SimpleTimingWheel(long tickMillis, int wheelSize) {
        if (tickMillis <= 0) {
            throw new IllegalArgumentException("tickMillis must be > 0");
        }
        if (wheelSize <= 0) {
            throw new IllegalArgumentException("wheelSize must be > 0");
        }
        this.tickMillis = tickMillis;
        this.tickNanos = millisToNanos(tickMillis);
        this.wheelSize = wheelSize;
        this.buckets = new List[wheelSize];
        for (int i = 0; i < wheelSize; i++) {
            buckets[i] = new ArrayList<>();
        }
    }

    /**
     * 启动 worker 线程。教学版只允许启动一次，停止后也不支持重新启动。
     */
    public void start() {
        lock.lock();
        try {
            if (started) {
                throw new IllegalStateException("timing wheel has already been started");
            }
            started = true;
            running = true;
            workerThread = new Thread(this::runLoop, "simple-timing-wheel");
            workerThread.setDaemon(true);
            workerThread.start();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 调度一个一次性任务。
     *
     * <p>{@code delayMillis == 0} 也会进入下一个 tick，而不是在调用线程中立即执行。
     *
     * @param task 要执行的任务
     * @param delayMillis 延迟毫秒数，必须大于等于 0
     */
    public void schedule(Runnable task, long delayMillis) {
        Objects.requireNonNull(task, "task");
        if (delayMillis < 0) {
            throw new IllegalArgumentException("delayMillis must be >= 0");
        }

        long ticks = ticksForDelay(delayMillis, tickMillis);
        lock.lock();
        try {
            if (!running) {
                throw new IllegalStateException("timing wheel is not running");
            }
            TimeoutPosition position = position(currentSlot, wheelSize, ticks);
            buckets[position.slot].add(new TimerTask(task, position.remainingRounds));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 停止 worker。已经在桶中但尚未到期的任务不会被继续执行。
     */
    public void stop() {
        Thread thread;
        lock.lock();
        try {
            running = false;
            thread = workerThread;
        } finally {
            lock.unlock();
        }
        if (thread != null) {
            LockSupport.unpark(thread);
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * 把延迟时间向上取整为 tick 数。
     *
     * <p>例如 tick=100ms 时，101ms 会转换成 2 个 tick。0ms 也至少等待 1 个 tick，
     * 这样所有任务都统一走“入槽 -> 推进 -> 执行”的路径。
     */
    static long ticksForDelay(long delayMillis, long tickMillis) {
        if (delayMillis < 0) {
            throw new IllegalArgumentException("delayMillis must be >= 0");
        }
        if (tickMillis <= 0) {
            throw new IllegalArgumentException("tickMillis must be > 0");
        }
        if (delayMillis == 0) {
            return 1L;
        }
        return ((delayMillis - 1L) / tickMillis) + 1L;
    }

    /**
     * 根据当前槽位和 tick 数，计算任务应该落入哪个槽位，以及还要等待几整圈。
     */
    static TimeoutPosition position(int currentSlot, int wheelSize, long ticks) {
        if (wheelSize <= 0) {
            throw new IllegalArgumentException("wheelSize must be > 0");
        }
        if (currentSlot < 0 || currentSlot >= wheelSize) {
            throw new IllegalArgumentException("currentSlot out of range");
        }
        if (ticks <= 0) {
            throw new IllegalArgumentException("ticks must be > 0");
        }

        int slot = (int) ((currentSlot + (ticks % wheelSize)) % wheelSize);
        long remainingRounds = (ticks - 1L) / wheelSize;
        return new TimeoutPosition(slot, remainingRounds);
    }

    private void runLoop() {
        while (running) {
            LockSupport.parkNanos(tickNanos);
            if (!running) {
                return;
            }
            runDueTasks(advanceOneTick());
        }
    }

    private List<TimerTask> advanceOneTick() {
        List<TimerTask> dueTasks = new ArrayList<>();
        lock.lock();
        try {
            // 每次醒来只推进一个槽位；教学版不根据真实 elapsed time 补偿晚醒。
            currentSlot = (currentSlot + 1) % wheelSize;
            List<TimerTask> bucket = buckets[currentSlot];
            Iterator<TimerTask> iterator = bucket.iterator();
            while (iterator.hasNext()) {
                TimerTask task = iterator.next();
                if (task.remainingRounds > 0) {
                    task.remainingRounds--;
                } else {
                    dueTasks.add(task);
                    iterator.remove();
                }
            }
        } finally {
            lock.unlock();
        }
        return dueTasks;
    }

    private void runDueTasks(List<TimerTask> dueTasks) {
        for (TimerTask task : dueTasks) {
            try {
                // 用户任务在锁外执行，避免任务耗时期间占住时间轮结构锁。
                task.runnable.run();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    private static long millisToNanos(long millis) {
        try {
            return Math.multiplyExact(millis, TimeUnit.MILLISECONDS.toNanos(1L));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("tickMillis is too large: " + millis, e);
        }
    }

    /**
     * 任务落点：目标槽位 + 还需要等待的完整轮数。
     */
    static final class TimeoutPosition {
        final int slot;
        final long remainingRounds;

        private TimeoutPosition(int slot, long remainingRounds) {
            this.slot = slot;
            this.remainingRounds = remainingRounds;
        }
    }

    /**
     * 桶中的任务节点。教学版不支持取消，所以只需要保存 Runnable 和剩余轮数。
     */
    private static final class TimerTask {
        private final Runnable runnable;
        private long remainingRounds;

        private TimerTask(Runnable runnable, long remainingRounds) {
            this.runnable = runnable;
            this.remainingRounds = remainingRounds;
        }
    }
}
