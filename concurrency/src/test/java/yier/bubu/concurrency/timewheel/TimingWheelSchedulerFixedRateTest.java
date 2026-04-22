package yier.bubu.concurrency.timewheel;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

public class TimingWheelSchedulerFixedRateTest {
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
    public void fixedRate_shouldNotOverlapAndShouldCatchUpWithZeroDelay() {
        ManualNanoClock clock = new ManualNanoClock(0L);
        RecordingExecutor executor = new RecordingExecutor();

        TimingWheelScheduler scheduler =
                new TimingWheelScheduler(Duration.ofMillis(100), 20, executor, clock, false);

        AtomicInteger ran = new AtomicInteger(0);
        ScheduledTask handle =
                scheduler.scheduleAtFixedRate(ran::incrementAndGet, Duration.ZERO, Duration.ofMillis(100));

        // initialDelay=0 -> first run is due, so it should be submitted once
        Assert.assertEquals(1, executor.size());

        // advance time but DO NOT execute the queued runnable => no overlap => no more submissions
        clock.advance(Duration.ofMillis(1_000));
        scheduler.drain();
        Assert.assertEquals(1, executor.size());

        // execute once => should reschedule again (we're behind => delay=0 => immediate submit)
        executor.runNext();
        Assert.assertEquals(1, ran.get());
        Assert.assertEquals(1, executor.size());

        // cancel should prevent future runs
        Assert.assertTrue(handle.cancel());
        executor.runNext(); // should be a no-op due to cancellation
        Assert.assertEquals(1, ran.get());
        Assert.assertEquals(0, executor.size());
    }
}

