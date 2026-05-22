package yier.bubu.algorithm;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

public class HanoiPcDemoTest {
    @Test
    public void hanoi_shouldPrintMovesAndReturnMoveCount() throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int moves;

        try {
            System.setOut(new PrintStream(output, true, "UTF-8"));

            moves = HanoiPcDemo.hanoi(3, 'A', 'C', 'B');
        } finally {
            System.setOut(originalOut);
        }

        String lineSeparator = System.lineSeparator();
        String expected = String.join(lineSeparator,
                "A -> C",
                "A -> B",
                "C -> B",
                "A -> C",
                "B -> A",
                "B -> C",
                "A -> C",
                "");

        Assert.assertEquals(7, moves);
        Assert.assertEquals(expected, output.toString("UTF-8"));
    }

    @Test
    public void hanoi_shouldReturnZeroWhenDiskCountIsNotPositive() {
        Assert.assertEquals(0, HanoiPcDemo.hanoi(0, 'A', 'C', 'B'));
    }
}
