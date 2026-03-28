package yier.bubu.jdk.nio.nonblocking;

import org.junit.Assert;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class NonBlockingEchoRoundTripTest {
    private static final long TIMEOUT_MILLIS = 6000L;

    // 主线测试应该让初学者先看到“服务端启动 -> 客户端发送 -> 收到响应”这条故事线。
    @Test
    public void exchange_shouldAckHello() throws Exception {
        NonBlockingEchoServer server = new NonBlockingEchoServer();
        NonBlockingEchoClient client = new NonBlockingEchoClient();

        FutureTask<Void> serverTask = runServer(server);
        String response = client.exchange(loopback(server.port()), "hello");

        Assert.assertEquals("ACK:hello", response);
        awaitServer(serverTask);
    }

    @Test
    public void exchange_shouldAckEmptyPayload() throws Exception {
        NonBlockingEchoServer server = new NonBlockingEchoServer();
        NonBlockingEchoClient client = new NonBlockingEchoClient();

        FutureTask<Void> serverTask = runServer(server);
        String response = client.exchange(loopback(server.port()), "");

        Assert.assertEquals("ACK:", response);
        awaitServer(serverTask);
    }

    private static FutureTask<Void> runServer(NonBlockingEchoServer server) {
        FutureTask<Void> task = new FutureTask<>(() -> {
            server.serveOnce();
            return null;
        });
        Thread thread = new Thread(task, "nonblocking-echo-server");
        thread.start();
        return task;
    }

    private static void awaitServer(FutureTask<Void> serverTask) throws Exception {
        serverTask.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static InetSocketAddress loopback(int port) throws Exception {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }
}
