package yier.bubu.jvm;

import org.junit.Assert;
import org.junit.Test;

public class TroubleshootingDemoConfigTest {

    @Test
    public void highCpuConfigFrom_shouldParseValues() {
        HighCpuDemo.Config config = HighCpuDemo.Config.from(new String[]{
                "--threads", "3",
                "--seconds", "9"
        });

        Assert.assertEquals(3, config.threads);
        Assert.assertEquals(9, config.seconds);
    }

    @Test
    public void staticLeakConfigFrom_shouldParseValues() {
        StaticMemoryLeakDemo.Config config = StaticMemoryLeakDemo.Config.from(new String[]{
                "--mb", "64",
                "--chunkMb", "4",
                "--reportEvery", "2",
                "--sleepSeconds", "5"
        });

        Assert.assertEquals(64, config.totalMb);
        Assert.assertEquals(4, config.chunkMb);
        Assert.assertEquals(2, config.reportEvery);
        Assert.assertEquals(5, config.sleepSeconds);
    }

    @Test
    public void threadBlockConfigFrom_shouldParseValues() {
        ThreadBlockDemo.Config config = ThreadBlockDemo.Config.from(new String[]{
                "--waiters", "4",
                "--sleepSeconds", "11"
        });

        Assert.assertEquals(4, config.waiters);
        Assert.assertEquals(11, config.sleepSeconds);
    }
}
