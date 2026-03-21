package yier.bubu.redis.hyperloglog;

import org.junit.Assert;
import org.junit.Test;

import java.util.function.ToLongFunction;

public class HyperLogLogTest {
    private static final ToLongFunction<Long> LONG_MIX_64 = new ToLongFunction<Long>() {
        @Override
        public long applyAsLong(Long value) {
            long x = value.longValue() + 0x9E3779B97F4A7C15L;
            x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
            x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
            return x ^ (x >>> 31);
        }
    };

    @Test
    public void hyperLogLog_shouldRejectPrecisionBelowSupportedRange() {
        assertIllegalArgument(new ThrowingRunnable() {
            @Override
            public void run() {
                new HyperLogLog<Long>(3);
            }
        });
    }

    @Test
    public void hyperLogLog_shouldRejectPrecisionAboveSupportedRange() {
        assertIllegalArgument(new ThrowingRunnable() {
            @Override
            public void run() {
                new HyperLogLog<Long>(19);
            }
        });
    }

    @Test
    public void hyperLogLog_shouldExposeMetadataForPrecisionTen() {
        HyperLogLog<Long> sketch = new HyperLogLog<Long>(10);

        Assert.assertEquals(10, sketch.precision());
        Assert.assertEquals(1024, sketch.registerCount());
        Assert.assertEquals(1.04D / Math.sqrt(1024.0D), sketch.standardError(), 0.0000001D);
    }

    @Test
    public void hyperLogLog_shouldUseInjectedHashFunction() {
        HyperLogLog<String> sketch = new HyperLogLog<String>(10, new ToLongFunction<String>() {
            @Override
            public long applyAsLong(String value) {
                return 0x123456789ABCDEFL;
            }
        });

        sketch.add("alpha");
        sketch.add("beta");

        Assert.assertEquals(1L, sketch.estimate());
    }

    @Test
    public void hyperLogLog_shouldNotInflateEstimateForRepeatedValues() {
        HyperLogLog<Long> sketch = new HyperLogLog<Long>(10, LONG_MIX_64);

        for (int i = 0; i < 500; i++) {
            sketch.add(42L);
        }

        Assert.assertTrue(sketch.estimate() <= 2L);
    }

    @Test
    public void hyperLogLog_shouldEstimateFiveThousandDistinctValuesWithinTenPercentAtPrecisionTen() {
        HyperLogLog<Long> sketch = new HyperLogLog<Long>(10, LONG_MIX_64);

        for (long value = 1L; value <= 5000L; value++) {
            sketch.add(value);
        }

        assertEstimateWithinWindow(sketch.estimate(), 5000L, 0.10D);
    }

    @Test
    public void hyperLogLog_shouldMergeTwoSketchesAsApproximateUnion() {
        HyperLogLog<Long> left = new HyperLogLog<Long>(10, LONG_MIX_64);
        HyperLogLog<Long> right = new HyperLogLog<Long>(10, LONG_MIX_64);

        for (long value = 1L; value <= 2500L; value++) {
            left.add(value);
        }
        for (long value = 2501L; value <= 5000L; value++) {
            right.add(value);
        }

        left.merge(right);

        assertEstimateWithinWindow(left.estimate(), 5000L, 0.10D);
    }

    @Test
    public void hyperLogLog_shouldRejectMergeAcrossDifferentPrecision() {
        final HyperLogLog<Long> coarse = new HyperLogLog<Long>(10, LONG_MIX_64);
        final HyperLogLog<Long> fine = new HyperLogLog<Long>(12, LONG_MIX_64);

        assertIllegalArgument(new ThrowingRunnable() {
            @Override
            public void run() {
                coarse.merge(fine);
            }
        });
    }

    @Test
    public void hyperLogLog_shouldRejectNullMergeTarget() {
        final HyperLogLog<Long> sketch = new HyperLogLog<Long>(10, LONG_MIX_64);

        assertNullPointer(new ThrowingRunnable() {
            @Override
            public void run() {
                sketch.merge(null);
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

    private static void assertNullPointer(ThrowingRunnable runnable) {
        try {
            runnable.run();
            Assert.fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
    }

    private static void assertEstimateWithinWindow(long actual, long expected, double tolerance) {
        long delta = Math.round(expected * tolerance);
        Assert.assertTrue(actual >= expected - delta);
        Assert.assertTrue(actual <= expected + delta);
    }
}
