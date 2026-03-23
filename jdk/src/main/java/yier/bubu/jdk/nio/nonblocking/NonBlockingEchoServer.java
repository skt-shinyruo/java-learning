package yier.bubu.jdk.nio.nonblocking;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Demonstrates a single-request non-blocking echo server built without {@link java.nio.channels.Selector}.
 * This package isolates the polling-based socket flow from the selector-based {@code NioReactorEchoServer}.
 */
public final class NonBlockingEchoServer {
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final int READ_BUFFER_SIZE = 4;

    public void serveOnce(AtomicInteger portRef, CountDownLatch portReady) throws Exception {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            server.configureBlocking(false);
            portRef.set(server.socket().getLocalPort());
            portReady.countDown();

            long deadline = System.nanoTime() + TIMEOUT_NANOS;
            try (SocketChannel client = awaitAccepted(server, deadline)) {
                client.configureBlocking(false);
                String request = readLine(client, deadline);
                writeFully(client, "ACK:" + request + "\n", deadline);
            }
        } finally {
            portReady.countDown();
        }
    }

    private SocketChannel awaitAccepted(ServerSocketChannel server, long deadline) throws IOException {
        while (true) {
            SocketChannel client = server.accept();
            if (client != null) {
                return client;
            }
            pauseIfNeeded(deadline, "accept a client");
        }
    }

    private String readLine(SocketChannel channel, long deadline) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_SIZE);

        while (true) {
            int read = channel.read(buffer);
            if (read == -1) {
                throw new IOException("channel closed before newline");
            }
            if (read == 0) {
                pauseIfNeeded(deadline, "read a request line");
                continue;
            }

            buffer.flip();
            while (buffer.hasRemaining()) {
                int next = buffer.get() & 0xFF;
                if (next == '\n') {
                    return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                }
                bytes.write(next);
            }
            buffer.clear();
        }
    }

    private void writeFully(SocketChannel channel, String value, long deadline) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer);
            if (written == 0) {
                pauseIfNeeded(deadline, "write a response line");
            }
        }
    }

    private void pauseIfNeeded(long deadline, String action) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
            throw new IllegalStateException("timed out waiting to " + action);
        }
        LockSupport.parkNanos(Math.min(PARK_NANOS, remaining));
    }
}
