package yier.bubu.jvm;

import org.junit.Assert;
import org.junit.Test;

public class GcPressureDemoTest {

    @Test
    public void configFrom_shouldParseExplicitValues() {
        GcPressureDemo.Config config = GcPressureDemo.Config.from(new String[]{
                "--seconds", "15",
                "--chunkKb", "512",
                "--retainEvery", "4",
                "--maxRetained", "32",
                "--reportEvery", "200"
        });

        Assert.assertEquals(15, config.seconds);
        Assert.assertEquals(512, config.chunkKb);
        Assert.assertEquals(4, config.retainEvery);
        Assert.assertEquals(32, config.maxRetained);
        Assert.assertEquals(200, config.reportEvery);
    }

    @Test
    public void configFrom_shouldNormalizeMinimums() {
        GcPressureDemo.Config config = GcPressureDemo.Config.from(new String[]{
                "--seconds", "0",
                "--chunkKb", "0",
                "--retainEvery", "0",
                "--maxRetained", "-1",
                "--reportEvery", "0"
        });

        Assert.assertEquals(1, config.seconds);
        Assert.assertEquals(1, config.chunkKb);
        Assert.assertEquals(1, config.retainEvery);
        Assert.assertEquals(0, config.maxRetained);
        Assert.assertEquals(1, config.reportEvery);
    }
}
