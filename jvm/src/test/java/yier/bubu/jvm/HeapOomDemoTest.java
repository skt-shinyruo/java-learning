package yier.bubu.jvm;

import org.junit.Assert;
import org.junit.Test;

public class HeapOomDemoTest {

    @Test
    public void configFrom_shouldParseExplicitValues() {
        HeapOomDemo.Config config = HeapOomDemo.Config.from(new String[]{
                "--mb", "128",
                "--chunkMb", "2",
                "--reportEvery", "7",
                "--sleepSeconds", "3"
        });

        Assert.assertEquals(128, config.totalMb);
        Assert.assertEquals(2, config.chunkMb);
        Assert.assertEquals(7, config.reportEvery);
        Assert.assertEquals(3, config.sleepSeconds);
    }

    @Test
    public void configFrom_shouldKeepSafeDefaults() {
        HeapOomDemo.Config config = HeapOomDemo.Config.from(new String[0]);

        Assert.assertEquals(96, config.totalMb);
        Assert.assertEquals(1, config.chunkMb);
        Assert.assertEquals(16, config.reportEvery);
        Assert.assertEquals(0, config.sleepSeconds);
    }
}
