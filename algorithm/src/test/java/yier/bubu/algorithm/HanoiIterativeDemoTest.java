package yier.bubu.algorithm;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

public class HanoiIterativeDemoTest {
    @Test
    public void hanoi_shouldPrintMovesInRecursiveOrder() throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(output, true, "UTF-8"));

            HanoiIterativeDemo.hanoi(3, 'A', 'B', 'C');
        } finally {
            System.setOut(originalOut);
        }

        String lineSeparator = System.lineSeparator();
        String expected = String.join(lineSeparator,
                "把第1个盘子从 A 移到 C",
                "把第2个盘子从 A 移到 B",
                "把第1个盘子从 C 移到 B",
                "把第3个盘子从 A 移到 C",
                "把第1个盘子从 B 移到 A",
                "把第2个盘子从 B 移到 C",
                "把第1个盘子从 A 移到 C",
                "");

        Assert.assertEquals(expected, output.toString("UTF-8"));
    }
}
