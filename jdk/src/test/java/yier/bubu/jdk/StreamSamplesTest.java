package yier.bubu.jdk;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class StreamSamplesTest {
    @Test
    public void sumOfSquaresOfEvenNumbers_shouldWork() {
        Assert.assertEquals(20, StreamSamples.sumOfSquaresOfEvenNumbers(Arrays.asList(1, 2, 3, 4)));
    }

    @Test
    public void sumOfSquaresOfEvenNumbers_shouldIgnoreNulls() {
        Assert.assertEquals(4, StreamSamples.sumOfSquaresOfEvenNumbers(Arrays.asList(null, 2, null)));
    }

    @Test
    public void sumOfSquaresOfEvenNumbers_emptyListShouldBeZero() {
        Assert.assertEquals(0, StreamSamples.sumOfSquaresOfEvenNumbers(Collections.<Integer>emptyList()));
    }
}

