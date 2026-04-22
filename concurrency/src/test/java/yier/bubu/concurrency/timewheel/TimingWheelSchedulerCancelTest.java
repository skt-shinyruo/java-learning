package yier.bubu.concurrency.timewheel;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

public class TimingWheelSchedulerCancelTest {
    @Test
    public void cancel_shouldBeIdempotentAndPreventExecution() throws Exception {
        ManualNanoClock clock = new ManualNanoClock(0L);
        Executor direct = Runnable::run;

        TimingWheelScheduler scheduler =
                new TimingWheelScheduler(Duration.ofMillis(100), 20, direct, clock, false);

        AtomicInteger ran = new AtomicInteger(0);
        ScheduledTask handle = scheduler.schedule(ran::incrementAndGet, Duration.ofMillis(200));

        // Cancellation should remove the task entry from the wheel to free memory early.
        java.lang.reflect.Field entryField = handle.getClass().getDeclaredField("entry");
        entryField.setAccessible(true);
        TimerTaskEntry entry = (TimerTaskEntry) entryField.get(handle);
        Assert.assertNotNull("should be enqueued in a bucket before cancel", entry.list);

        Assert.assertTrue(handle.cancel());
        Assert.assertFalse("second cancel is idempotent", handle.cancel());
        Assert.assertNull("cancel should unlink from bucket list", entry.list);

        clock.advance(Duration.ofMillis(200));
        scheduler.drain();
        Assert.assertEquals(0, ran.get());
    }
}
