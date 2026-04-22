package yier.bubu.concurrency.timewheel;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TimingWheelSchedulerFixedDelayTest {
    private static final class RecordingExecutor implements Executor {
        private final Deque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queue.addLast(command);
        }

        int size() {
            return queue.size();
        }

        void runNext() {
            queue.removeFirst().run();
        }
    }

    @Test
    public void fixedDelay_shouldScheduleNextRunFromCompletionTime() {
        ManualNanoClock clock = new ManualNanoClock(0L);
        RecordingExecutor executor = new RecordingExecutor();

        // Use smaller tick to avoid rounding noise in this test.
        TimingWheelScheduler scheduler =
                new TimingWheelScheduler(Duration.ofMillis(10), 20, executor, clock, false);

        AtomicInteger ran = new AtomicInteger(0);

        scheduler.scheduleWithFixedDelay(() -> {
            ran.incrementAndGet();
            // simulate work: completion time moves forward by 50ms
            clock.advance(Duration.ofMillis(50));
        }, Duration.ZERO, Duration.ofMillis(100));

        Assert.assertEquals(1, executor.size());
        executor.runNext();
        Assert.assertEquals(1, ran.get());

        // If fixed-delay uses completion time (50ms), next should be at 150ms.
        clock.setNanos(TimeUnit.MILLISECONDS.toNanos(100));
        scheduler.drain();
        Assert.assertEquals("should not run at 100ms", 0, executor.size());

        clock.setNanos(TimeUnit.MILLISECONDS.toNanos(149));
        scheduler.drain();
        Assert.assertEquals(0, executor.size());

        clock.setNanos(TimeUnit.MILLISECONDS.toNanos(150));
        scheduler.drain();
        Assert.assertEquals(1, executor.size());
    }
}

