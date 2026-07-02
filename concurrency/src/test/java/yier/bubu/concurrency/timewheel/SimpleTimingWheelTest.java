package yier.bubu.concurrency.timewheel;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SimpleTimingWheelTest {
    @Test
    public void ticksForDelay_shouldRoundUpAndRunZeroDelayOnNextTick() {
        Assert.assertEquals(1L, SimpleTimingWheel.ticksForDelay(0L, 100L));
        Assert.assertEquals(1L, SimpleTimingWheel.ticksForDelay(1L, 100L));
        Assert.assertEquals(1L, SimpleTimingWheel.ticksForDelay(100L, 100L));
        Assert.assertEquals(2L, SimpleTimingWheel.ticksForDelay(101L, 100L));
        Assert.assertEquals(2L, SimpleTimingWheel.ticksForDelay(150L, 100L));
    }

    @Test
    public void position_shouldComputeSlotAndRemainingRounds() {
        SimpleTimingWheel.TimeoutPosition oneTick =
                SimpleTimingWheel.position(0, 8, 1L);
        Assert.assertEquals(1, oneTick.slot);
        Assert.assertEquals(0L, oneTick.remainingRounds);

        SimpleTimingWheel.TimeoutPosition oneFullRound =
                SimpleTimingWheel.position(0, 8, 8L);
        Assert.assertEquals(0, oneFullRound.slot);
        Assert.assertEquals(0L, oneFullRound.remainingRounds);

        SimpleTimingWheel.TimeoutPosition oneFullRoundPlusOne =
                SimpleTimingWheel.position(0, 8, 9L);
        Assert.assertEquals(1, oneFullRoundPlusOne.slot);
        Assert.assertEquals(1L, oneFullRoundPlusOne.remainingRounds);
    }

    @Test
    public void constructor_shouldRejectInvalidArguments() {
        try {
            new SimpleTimingWheel(0L, 8);
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            Assert.assertEquals("tickMillis must be > 0", expected.getMessage());
        }

        try {
            new SimpleTimingWheel(100L, 0);
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            Assert.assertEquals("wheelSize must be > 0", expected.getMessage());
        }
    }

    @Test
    public void schedule_shouldRequireStartedWheelAndNonNegativeDelay() {
        SimpleTimingWheel wheel = new SimpleTimingWheel(10L, 4);

        try {
            wheel.schedule(() -> {
            }, 10L);
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            Assert.assertEquals("timing wheel is not running", expected.getMessage());
        }

        wheel.start();
        try {
            wheel.schedule(() -> {
            }, -1L);
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            Assert.assertEquals("delayMillis must be >= 0", expected.getMessage());
        } finally {
            wheel.stop();
        }
    }

    @Test
    public void start_shouldBeOneShotLifecycle() {
        SimpleTimingWheel wheel = new SimpleTimingWheel(10L, 4);
        wheel.start();

        try {
            wheel.start();
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            Assert.assertEquals("timing wheel has already been started", expected.getMessage());
        } finally {
            wheel.stop();
        }

        try {
            wheel.start();
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            Assert.assertEquals("timing wheel has already been started", expected.getMessage());
        }
    }

    @Test
    public void schedule_shouldRunTaskAfterTickAdvances() throws Exception {
        SimpleTimingWheel wheel = new SimpleTimingWheel(10L, 8);
        CountDownLatch latch = new CountDownLatch(1);

        wheel.start();
        try {
            wheel.schedule(latch::countDown, 0L);
            Assert.assertTrue("task should run on a later tick", latch.await(1, TimeUnit.SECONDS));
        } finally {
            wheel.stop();
        }
    }
}
