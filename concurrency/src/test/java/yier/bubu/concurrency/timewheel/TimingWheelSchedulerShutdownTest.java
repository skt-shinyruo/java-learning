package yier.bubu.concurrency.timewheel;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public class TimingWheelSchedulerShutdownTest {
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
    public void shutdown_shouldRejectNewSchedules() {
        ManualNanoClock clock = new ManualNanoClock(0L);
        Executor direct = Runnable::run;
        TimingWheelScheduler scheduler =
                new TimingWheelScheduler(Duration.ofMillis(100), 20, direct, clock, false);

        scheduler.shutdown();
        try {
            scheduler.schedule(() -> {
            }, Duration.ZERO);
            Assert.fail("expected RejectedExecutionException");
        } catch (RejectedExecutionException expected) {
        }
    }

    @Test
    public void shutdown_shouldNotPreventExistingOneShotFromRunning() {
        ManualNanoClock clock = new ManualNanoClock(0L);
        Executor direct = Runnable::run;
        TimingWheelScheduler scheduler =
                new TimingWheelScheduler(Duration.ofMillis(100), 20, direct, clock, false);

        AtomicInteger ran = new AtomicInteger(0);
        scheduler.schedule(ran::incrementAndGet, Duration.ofMillis(200));

        scheduler.shutdown();

        clock.advance(Duration.ofMillis(200));
        scheduler.drain();
        Assert.assertEquals(1, ran.get());
    }

    @Test
    public void shutdown_shouldStopPeriodicReschedule() {
        ManualNanoClock clock = new ManualNanoClock(0L);
        RecordingExecutor executor = new RecordingExecutor();
        TimingWheelScheduler scheduler =
                new TimingWheelScheduler(Duration.ofMillis(100), 20, executor, clock, false);

        AtomicInteger ran = new AtomicInteger(0);
        scheduler.scheduleAtFixedRate(ran::incrementAndGet, Duration.ZERO, Duration.ofMillis(100));

        Assert.assertEquals(1, executor.size());

        // Shut down before the first run executes.
        scheduler.shutdown();
        executor.runNext();

        Assert.assertEquals(1, ran.get());
        Assert.assertEquals("no reschedule after shutdown", 0, executor.size());
    }
}

