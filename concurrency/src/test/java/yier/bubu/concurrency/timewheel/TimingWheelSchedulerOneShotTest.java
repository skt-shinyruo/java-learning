package yier.bubu.concurrency.timewheel;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TimingWheelSchedulerOneShotTest {
    @Test
    public void schedule_shouldRunAtExactTickWhenDelayAlignsToTick() {
        ManualNanoClock clock = new ManualNanoClock(0L);
        Executor direct = Runnable::run;

        TimingWheelScheduler scheduler =
                new TimingWheelScheduler(Duration.ofMillis(100), 20, direct, clock, false);

        AtomicInteger ran = new AtomicInteger(0);
        AtomicLong runAt = new AtomicLong(-1L);

        scheduler.schedule(() -> {
            ran.incrementAndGet();
            runAt.set(clock.nowNanos());
        }, Duration.ofMillis(200));

        clock.advance(Duration.ofMillis(199));
        scheduler.drain();
        Assert.assertEquals(0, ran.get());

        clock.advance(Duration.ofMillis(1));
        scheduler.drain();

        Assert.assertEquals(1, ran.get());
        Assert.assertEquals(TimeUnit.MILLISECONDS.toNanos(200), runAt.get());
    }

    @Test
    public void schedule_shouldNotRunBeforeDeadlineEvenWhenDelayIsNotTickAligned() {
        ManualNanoClock clock = new ManualNanoClock(0L);
        Executor direct = Runnable::run;

        TimingWheelScheduler scheduler =
                new TimingWheelScheduler(Duration.ofMillis(100), 20, direct, clock, false);

        AtomicInteger ran = new AtomicInteger(0);
        AtomicLong runAt = new AtomicLong(-1L);

        // deadline=150ms, but expiration must be ceil-to-tick => 200ms
        scheduler.schedule(() -> {
            ran.incrementAndGet();
            runAt.set(clock.nowNanos());
        }, Duration.ofMillis(150));

        clock.advance(Duration.ofMillis(199));
        scheduler.drain();
        Assert.assertEquals("not due yet", 0, ran.get());

        clock.advance(Duration.ofMillis(1));
        scheduler.drain();

        Assert.assertEquals(1, ran.get());
        Assert.assertEquals(TimeUnit.MILLISECONDS.toNanos(200), runAt.get());
    }
}

