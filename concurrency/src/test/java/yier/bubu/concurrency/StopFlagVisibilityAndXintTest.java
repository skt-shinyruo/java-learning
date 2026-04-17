package yier.bubu.concurrency;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import yier.bubu.concurrency.jmm.XintStopFlagDemo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 配套文档：concurrency/docs/non-volatile-stop-flag-and-xint.md
 */
public class StopFlagVisibilityAndXintTest {
    private static final long STOP_DELAY_MILLIS = 20L;
    private static final long JOIN_TIMEOUT_MILLIS = 1000L;

    @Test(timeout = 3000)
    public void volatile_mode_shouldStopReliably() throws Exception {
        XintStopFlagDemo.ExperimentResult result =
                XintStopFlagDemo.runExperiment(
                        XintStopFlagDemo.Mode.VOLATILE,
                        STOP_DELAY_MILLIS,
                        JOIN_TIMEOUT_MILLIS,
                        false);

        Assert.assertTrue(result.stoppedWithinTimeout());
        Assert.assertFalse(result.workerAliveAfterJoin());
    }

    @Ignore("说明性实验：-Xint 下 plain boolean 通常更容易停，但这不是规范保证，不能做稳定断言。")
    @Test(timeout = 8000)
    public void plain_mode_withXint_oftenStopsButRemainsImplementationDependent() throws Exception {
        String output = runPlainDemoWithXint();

        Assert.assertTrue(output.contains("interpreterOnly=true"));
        Assert.assertTrue(output.contains("stoppedWithinTimeout=true"));
    }

    private static String runPlainDemoWithXint() throws Exception {
        List<String> command = new ArrayList<String>();
        command.add(System.getProperty("java.home") + "/bin/java");
        command.addAll(Arrays.asList("-Xint"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(XintStopFlagDemo.class.getName());
        command.add("plain");
        command.add(Long.toString(STOP_DELAY_MILLIS));
        command.add(Long.toString(JOIN_TIMEOUT_MILLIS));

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        StringBuilder stdout = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stdout.append(line).append('\n');
            }
        }

        boolean finished = process.waitFor(5, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            Assert.fail("subprocess did not finish in time");
        }
        Assert.assertEquals(0, process.exitValue());
        return stdout.toString();
    }
}
