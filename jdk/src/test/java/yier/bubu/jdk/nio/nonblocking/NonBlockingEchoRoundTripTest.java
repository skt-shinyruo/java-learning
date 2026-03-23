package yier.bubu.jdk.nio.nonblocking;

import org.junit.Assert;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class NonBlockingEchoRoundTripTest {
    private static final long TIMEOUT_MILLIS = 6000L;

    @Test
    public void exchange_shouldAckHello() throws Exception {
        AtomicInteger portRef = new AtomicInteger(-1);
        CountDownLatch portReady = new CountDownLatch(1);
        AtomicReference<Throwable> failureRef = new AtomicReference<>();

        NonBlockingEchoServer server = new NonBlockingEchoServer();
        NonBlockingEchoClient client = new NonBlockingEchoClient();

        Thread serverThread = startServer(server, portRef, portReady, failureRef);
        try {
            awaitPort(portReady, portRef);
            String response = client.exchange(loopback(portRef.get()), "hello");
            Assert.assertEquals("ACK:hello", response);
            awaitServerExit(serverThread, failureRef);
        } finally {
            serverThread.join(TIMEOUT_MILLIS);
        }
    }

    @Test
    public void exchange_shouldAckEmptyPayload() throws Exception {
        AtomicInteger portRef = new AtomicInteger(-1);
        CountDownLatch portReady = new CountDownLatch(1);
        AtomicReference<Throwable> failureRef = new AtomicReference<>();

        NonBlockingEchoServer server = new NonBlockingEchoServer();
        NonBlockingEchoClient client = new NonBlockingEchoClient();

        Thread serverThread = startServer(server, portRef, portReady, failureRef);
        try {
            awaitPort(portReady, portRef);
            String response = client.exchange(loopback(portRef.get()), "");
            Assert.assertEquals("ACK:", response);
            awaitServerExit(serverThread, failureRef);
        } finally {
            serverThread.join(TIMEOUT_MILLIS);
        }
    }

    @Test
    public void serveOnce_shouldSurfaceIllegalStateExceptionWhenAcceptTimesOut() throws Exception {
        AtomicInteger portRef = new AtomicInteger(-1);
        CountDownLatch portReady = new CountDownLatch(1);
        AtomicReference<Throwable> failureRef = new AtomicReference<>();

        NonBlockingEchoServer server = new NonBlockingEchoServer();

        Thread serverThread = startServer(server, portRef, portReady, failureRef);
        try {
            awaitPort(portReady, portRef);
            Throwable failure = awaitServerFailure(serverThread, failureRef);
            Assert.assertTrue("timeout should surface as IllegalStateException but was " + failure,
                    failure instanceof IllegalStateException);
            Assert.assertEquals("timed out waiting to accept a client", failure.getMessage());
        } finally {
            serverThread.join(TIMEOUT_MILLIS);
        }
    }

    private static Thread startServer(NonBlockingEchoServer server,
                                      AtomicInteger portRef,
                                      CountDownLatch portReady,
                                      AtomicReference<Throwable> failureRef) {
        Thread thread = new Thread(() -> {
            try {
                server.serveOnce(portRef, portReady);
            } catch (Throwable t) {
                failureRef.compareAndSet(null, t);
            }
        }, "nonblocking-echo-server");
        thread.start();
        return thread;
    }

    private static void awaitPort(CountDownLatch portReady, AtomicInteger portRef) throws InterruptedException {
        Assert.assertTrue("server should publish a port", portReady.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        Assert.assertTrue("port should be assigned", portRef.get() > 0);
    }

    private static void awaitServerExit(Thread serverThread, AtomicReference<Throwable> failureRef) throws Exception {
        awaitServerStopped(serverThread);
        Throwable failure = failureRef.get();
        if (failure == null) {
            return;
        }
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new RuntimeException(failure);
    }

    private static Throwable awaitServerFailure(Thread serverThread, AtomicReference<Throwable> failureRef)
            throws InterruptedException {
        awaitServerStopped(serverThread);
        Throwable failure = failureRef.get();
        Assert.assertNotNull("server thread should fail", failure);
        return failure;
    }

    private static void awaitServerStopped(Thread serverThread) throws InterruptedException {
        serverThread.join(TIMEOUT_MILLIS);
        Assert.assertFalse("server thread should exit", serverThread.isAlive());
    }

    private static InetSocketAddress loopback(int port) throws Exception {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }
}
