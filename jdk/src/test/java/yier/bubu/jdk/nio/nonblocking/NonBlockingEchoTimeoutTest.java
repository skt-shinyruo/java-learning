package yier.bubu.jdk.nio.nonblocking;

import org.junit.Assert;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class NonBlockingEchoTimeoutTest {
    private static final long TIMEOUT_MILLIS = 6000L;

    @Test
    public void serveOnce_shouldSurfaceIllegalStateExceptionWhenAcceptTimesOut() throws Exception {
        NonBlockingEchoServer server = new NonBlockingEchoServer();

        FutureTask<Void> serverTask = runServer(server);
        Throwable failure = awaitServerFailure(serverTask);

        Assert.assertTrue("timeout should surface as IllegalStateException but was " + failure,
                failure instanceof IllegalStateException);
        Assert.assertEquals("timed out waiting to accept a client", failure.getMessage());
    }

    @Test
    public void exchange_shouldSurfaceIllegalStateExceptionWhenClientReadTimesOut() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            NonBlockingEchoClient client = new NonBlockingEchoClient(TimeUnit.MILLISECONDS.toNanos(50L));

            FutureTask<Void> serverTask = startSilentServer(server);
            try {
                try {
                    client.exchange(loopback(port), "hello");
                    Assert.fail("client should time out while waiting for a response");
                } catch (IllegalStateException expected) {
                    Assert.assertEquals("timed out waiting to read a response line", expected.getMessage());
                }
            } finally {
                awaitServer(serverTask);
            }
        }
    }

    private static FutureTask<Void> runServer(NonBlockingEchoServer server) {
        FutureTask<Void> task = new FutureTask<>(() -> {
            server.serveOnce();
            return null;
        });
        new Thread(task, "nonblocking-echo-server-timeout").start();
        return task;
    }

    private static FutureTask<Void> startSilentServer(ServerSocket server) {
        FutureTask<Void> task = new FutureTask<>(() -> {
            try (Socket ignored = server.accept()) {
                TimeUnit.MILLISECONDS.sleep(100L);
            }
            return null;
        });
        new Thread(task, "nonblocking-echo-silent-server").start();
        return task;
    }

    private static void awaitServer(FutureTask<Void> serverTask) throws Exception {
        serverTask.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static Throwable awaitServerFailure(FutureTask<Void> serverTask) throws Exception {
        try {
            awaitServer(serverTask);
            Assert.fail("server should fail");
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private static InetSocketAddress loopback(int port) throws Exception {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }
}
