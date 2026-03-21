package yier.bubu.redis.hyperloglog;

import org.junit.Assert;
import org.junit.Test;

public class DailyActiveUserCounterTest {
    @Test
    public void counter_shouldEstimateKnownVisitorsInBusinessLanguage() {
        DailyActiveUserCounter counter = new DailyActiveUserCounter(10);

        for (long userId = 1L; userId <= 5000L; userId++) {
            counter.recordVisit(userId);
        }

        assertEstimateWithinWindow(counter.estimateDailyActiveUsers(), 5000L, 0.10D);
    }

    @Test
    public void counter_shouldNotInflateForRepeatedVisitsFromSameUser() {
        DailyActiveUserCounter counter = new DailyActiveUserCounter(10);

        for (int i = 0; i < 500; i++) {
            counter.recordVisit(42L);
        }

        Assert.assertTrue(counter.estimateDailyActiveUsers() <= 2L);
    }

    @Test
    public void counter_shouldMergeShardLevelUvCounters() {
        DailyActiveUserCounter left = new DailyActiveUserCounter(10);
        DailyActiveUserCounter right = new DailyActiveUserCounter(10);

        for (long userId = 1L; userId <= 2500L; userId++) {
            left.recordVisit(userId);
        }
        for (long userId = 2501L; userId <= 5000L; userId++) {
            right.recordVisit(userId);
        }

        left.merge(right);

        assertEstimateWithinWindow(left.estimateDailyActiveUsers(), 5000L, 0.10D);
    }

    @Test
    public void counter_shouldRejectMergeAcrossDifferentPrecision() {
        final DailyActiveUserCounter coarse = new DailyActiveUserCounter(10);
        final DailyActiveUserCounter fine = new DailyActiveUserCounter(12);

        assertIllegalArgument(new ThrowingRunnable() {
            @Override
            public void run() {
                coarse.merge(fine);
            }
        });
    }

    private interface ThrowingRunnable {
        void run();
    }

    private static void assertIllegalArgument(ThrowingRunnable runnable) {
        try {
            runnable.run();
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void assertEstimateWithinWindow(long actual, long expected, double tolerance) {
        long delta = Math.round(expected * tolerance);
        Assert.assertTrue(actual >= expected - delta);
        Assert.assertTrue(actual <= expected + delta);
    }
}
