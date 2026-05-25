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
    public void highCpuConfigFrom_shouldNormalizeMinimums() {
        HighCpuDemo.Config config = HighCpuDemo.Config.from(new String[]{
                "--threads", "0",
                "--seconds", "0"
        });

        Assert.assertEquals(1, config.threads);
        Assert.assertEquals(1, config.seconds);
    }

    @Test
    public void highCpuConfigFrom_shouldKeepSafeDefaults() {
        HighCpuDemo.Config config = HighCpuDemo.Config.from(new String[0]);

        Assert.assertEquals(1, config.threads);
        Assert.assertEquals(120, config.seconds);
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
    public void staticLeakConfigFrom_shouldNormalizeMinimums() {
        StaticMemoryLeakDemo.Config config = StaticMemoryLeakDemo.Config.from(new String[]{
                "--mb", "0",
                "--chunkMb", "0",
                "--reportEvery", "0",
                "--sleepSeconds", "-1"
        });

        Assert.assertEquals(1, config.totalMb);
        Assert.assertEquals(1, config.chunkMb);
        Assert.assertEquals(1, config.reportEvery);
        Assert.assertEquals(0, config.sleepSeconds);
    }

    @Test
    public void staticLeakConfigFrom_shouldKeepSafeDefaults() {
        StaticMemoryLeakDemo.Config config = StaticMemoryLeakDemo.Config.from(new String[0]);

        Assert.assertEquals(64, config.totalMb);
        Assert.assertEquals(1, config.chunkMb);
        Assert.assertEquals(8, config.reportEvery);
        Assert.assertEquals(120, config.sleepSeconds);
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

    @Test
    public void threadBlockConfigFrom_shouldNormalizeMinimums() {
        ThreadBlockDemo.Config config = ThreadBlockDemo.Config.from(new String[]{
                "--waiters", "0",
                "--sleepSeconds", "0"
        });

        Assert.assertEquals(1, config.waiters);
        Assert.assertEquals(1, config.sleepSeconds);
    }

    @Test
    public void threadBlockConfigFrom_shouldKeepSafeDefaults() {
        ThreadBlockDemo.Config config = ThreadBlockDemo.Config.from(new String[0]);

        Assert.assertEquals(3, config.waiters);
        Assert.assertEquals(120, config.sleepSeconds);
    }
}
