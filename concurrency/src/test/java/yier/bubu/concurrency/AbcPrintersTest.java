package yier.bubu.concurrency;

import org.junit.Assert;
import org.junit.Test;

public class AbcPrintersTest {
    @Test(timeout = 2000)
    public void allImplementations_shouldPrintStrictOrder() throws Exception {
        int rounds = 50;
        String expected = repeat("ABC", rounds);

        Assert.assertEquals(expected, AbcPrinters.printBySemaphore(rounds));
        Assert.assertEquals(expected, AbcPrinters.printByCondition(rounds));
        Assert.assertEquals(expected, AbcPrinters.printByWaitNotifyAll(rounds));
        Assert.assertEquals(expected, AbcPrinters.printByBlockingQueue(rounds));
        Assert.assertEquals(expected, AbcPrinters.printByLockSupport(rounds));
    }

    @Test(timeout = 2000)
    public void roundsZero_shouldReturnEmpty() throws Exception {
        Assert.assertEquals("", AbcPrinters.printBySemaphore(0));
        Assert.assertEquals("", AbcPrinters.printByCondition(0));
        Assert.assertEquals("", AbcPrinters.printByWaitNotifyAll(0));
        Assert.assertEquals("", AbcPrinters.printByBlockingQueue(0));
        Assert.assertEquals("", AbcPrinters.printByLockSupport(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeRounds_shouldThrow() throws Exception {
        AbcPrinters.printBySemaphore(-1);
    }

    private static String repeat(String unit, int rounds) {
        StringBuilder sb = new StringBuilder(unit.length() * rounds);
        for (int i = 0; i < rounds; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }
}
