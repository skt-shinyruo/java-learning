package yier.bubu.nio;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

public class EchoIoModelDemoTest {
    @Test
    public void runSyncBlocking_shouldEchoWithBlockingReadEvent() throws Exception {
        EchoIoModelDemo.EchoResult result = EchoIoModelDemo.runSyncBlocking();

        assertEcho(result, "sync-blocking");
        assertContains(result.events(), "client: blocking read waits for echo");
        assertContains(result.events(), "server: echo ping");
    }

    @Test
    public void runSyncNonBlocking_shouldEchoWithSelectorEvents() throws Exception {
        EchoIoModelDemo.EchoResult result = EchoIoModelDemo.runSyncNonBlocking();

        assertEcho(result, "sync-nonblocking");
        assertContains(result.events(), "client: selector reports connect ready");
        assertContains(result.events(), "client: selector reports read ready");
        assertContains(result.events(), "server: echo ping");
    }

    @Test
    public void runAsyncBlocking_shouldEchoWithFutureGetEvent() throws Exception {
        EchoIoModelDemo.EchoResult result = EchoIoModelDemo.runAsyncBlocking();

        assertEcho(result, "async-blocking");
        assertContains(result.events(), "client: Future.get waits for read");
        assertContains(result.events(), "server: echo ping");
    }

    @Test
    public void runAsyncNonBlocking_shouldEchoWithCompletionHandlerEvent() throws Exception {
        EchoIoModelDemo.EchoResult result = EchoIoModelDemo.runAsyncNonBlocking();

        assertEcho(result, "async-nonblocking");
        assertContains(result.events(), "client: CompletionHandler read ping");
        assertContains(result.events(), "server: echo ping");
    }

    @Test
    public void run_shouldRejectUnknownMode() throws Exception {
        try {
            EchoIoModelDemo.run("unknown");
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            Assert.assertEquals("Unknown echo mode: unknown", expected.getMessage());
        }
    }

    @Test
    public void appEchoCommand_shouldPrintEchoResult() throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, "UTF-8"));

            NioDirectMemoryApp.main(new String[]{"echo", "sync-blocking"});
        } finally {
            System.setOut(originalOut);
        }

        String text = output.toString("UTF-8");
        Assert.assertTrue(text.contains("[EchoIoModelDemo]"));
        Assert.assertTrue(text.contains("mode=sync-blocking"));
        Assert.assertTrue(text.contains("response=ping"));
        Assert.assertTrue(text.contains("client: blocking read waits for echo"));
    }

    private void assertEcho(EchoIoModelDemo.EchoResult result, String mode) {
        Assert.assertEquals(mode, result.mode());
        Assert.assertEquals("ping", result.request());
        Assert.assertEquals("ping", result.response());
    }

    private void assertContains(List<String> events, String expected) {
        Assert.assertTrue("Expected event log to contain: " + expected + ", actual=" + events,
                events.contains(expected));
    }
}
