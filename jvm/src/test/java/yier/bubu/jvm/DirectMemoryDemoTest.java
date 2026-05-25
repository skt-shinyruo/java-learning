package yier.bubu.jvm;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

public class DirectMemoryDemoTest {

    @Test
    public void configFrom_shouldNormalizeMinimums() {
        DirectMemoryDemo.Config config = DirectMemoryDemo.Config.from(new String[]{
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
    public void run_shouldRejectChunkTooLargeForByteBuffer() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(out, true, "UTF-8"));

            DirectMemoryDemo.run(new String[]{
                    "--mb", "1",
                    "--chunkMb", "2048",
                    "--touch", "false"
            });
        } finally {
            System.setOut(original);
        }

        String text = out.toString("UTF-8");
        Assert.assertTrue(text.contains("Invalid args: --chunkMb must fit in a positive int-sized direct buffer"));
    }

    @Test
    public void run_shouldHandleZeroReportEvery() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(out, true, "UTF-8"));

            DirectMemoryDemo.run(new String[]{
                    "--mb", "1",
                    "--chunkMb", "1",
                    "--reportEvery", "0",
                    "--touch", "false"
            });
        } finally {
            System.setOut(original);
        }

        String text = out.toString("UTF-8");
        Assert.assertTrue(text.contains("[DirectMemoryDemo]"));
        Assert.assertTrue(text.contains("allocatedBuffers=1"));
    }
}
