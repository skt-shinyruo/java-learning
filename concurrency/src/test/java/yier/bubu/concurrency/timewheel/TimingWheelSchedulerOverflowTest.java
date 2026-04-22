package yier.bubu.concurrency.timewheel;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TimingWheelSchedulerOverflowTest {
    @Test
    public void longDelay_shouldBeHandledByOverflowWheelAndStillFireAtDeadlineTick() {
        ManualNanoClock clock = new ManualNanoClock(0L);
        Executor direct = Runnable::run;

        // tick=100ms, wheelSize=10 => base interval=1000ms
        TimingWheelScheduler scheduler =
                new TimingWheelScheduler(Duration.ofMillis(100), 10, direct, clock, false);

        AtomicInteger ran = new AtomicInteger(0);
        AtomicLong runAt = new AtomicLong(-1L);

        scheduler.schedule(() -> {
            ran.incrementAndGet();
            runAt.set(clock.nowNanos());
        }, Duration.ofMillis(2_500));

        clock.advance(Duration.ofMillis(2_499));
        scheduler.drain();
        Assert.assertEquals(0, ran.get());

        clock.advance(Duration.ofMillis(1));
        scheduler.drain();
        Assert.assertEquals(1, ran.get());
        Assert.assertEquals(TimeUnit.MILLISECONDS.toNanos(2_500), runAt.get());
    }
}

