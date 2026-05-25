package yier.bubu.jvm;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

public class JvmLabAppTest {

    @Test
    public void help_shouldListFirstBatchCommands() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(out, true, "UTF-8"));

            JvmLabApp.main(new String[]{"help"});
        } finally {
            System.setOut(original);
        }

        String text = out.toString("UTF-8");
        Assert.assertTrue(text.contains("heap-oom"));
        Assert.assertTrue(text.contains("gc-pressure"));
        Assert.assertTrue(text.contains("high-cpu"));
        Assert.assertTrue(text.contains("static-leak"));
        Assert.assertTrue(text.contains("deadlock"));
        Assert.assertTrue(text.contains("thread-block"));
        Assert.assertTrue(text.contains("Manual failure labs"));
    }
}
