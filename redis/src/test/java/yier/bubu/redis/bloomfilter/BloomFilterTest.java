package yier.bubu.redis.bloomfilter;

import org.junit.Assert;
import org.junit.Test;

public class BloomFilterTest {
    @Test
    public void bloomFilter_shouldReportInsertedValuesAsPresent() {
        BloomFilter<String> filter = new BloomFilter<String>(100, 0.01);

        filter.put("sku-1001");
        filter.put("sku-1002");

        Assert.assertTrue(filter.mightContain("sku-1001"));
        Assert.assertTrue(filter.mightContain("sku-1002"));
    }

    @Test
    public void bloomFilter_shouldExposePositiveSizingMetadata() {
        BloomFilter<String> filter = new BloomFilter<String>(1_000, 0.01);

        Assert.assertEquals(1_000L, filter.expectedInsertions());
        Assert.assertEquals(0.01, filter.falsePositiveProbability(), 0.0);
        Assert.assertTrue(filter.bitSize() > 0);
        Assert.assertTrue(filter.hashFunctionCount() > 0);
    }

    @Test
    public void bloomFilter_shouldRejectInvalidConstructorArguments() {
        assertIllegalArgument(new ThrowingRunnable() {
            @Override
            public void run() {
                new BloomFilter<String>(0, 0.01);
            }
        });
        assertIllegalArgument(new ThrowingRunnable() {
            @Override
            public void run() {
                new BloomFilter<String>(10, 1.0);
            }
        });
    }

    @Test
    public void bloomFilter_shouldUseMoreBitsForLowerFalsePositiveProbability() {
        BloomFilter<String> loose = new BloomFilter<String>(1_000, 0.05);
        BloomFilter<String> strict = new BloomFilter<String>(1_000, 0.001);

        Assert.assertTrue(strict.bitSize() > loose.bitSize());
    }

    @Test
    public void bloomFilter_shouldRejectNullValues() {
        final BloomFilter<String> filter = new BloomFilter<String>(100, 0.01);

        assertNullPointer(new ThrowingRunnable() {
            @Override
            public void run() {
                filter.put(null);
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
}
