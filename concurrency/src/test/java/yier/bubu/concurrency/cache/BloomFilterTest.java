package yier.bubu.concurrency.cache;

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
}
