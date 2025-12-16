package yier.bubu.concurrency;

import org.junit.Assert;
import org.junit.Test;

public class ParallelCounterTest {
    @Test
    public void incrementInParallel_shouldReturnExpectedCount() throws Exception {
        int result = ParallelCounter.incrementInParallel(1_000, 4);
        Assert.assertEquals(1_000, result);
    }
}

