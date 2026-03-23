package yier.bubu.jdk.nio.nonblocking;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Demonstrates a single-request non-blocking echo client built without {@link java.nio.channels.Selector}.
 * This package keeps the polling-based socket example separate from the selector-driven reactor example.
 */
public final class NonBlockingEchoClient {
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final int READ_BUFFER_SIZE = 4;
    private final long timeoutNanos;

    public NonBlockingEchoClient() {
        this(TIMEOUT_NANOS);
    }

    NonBlockingEchoClient(long timeoutNanos) {
        this.timeoutNanos = timeoutNanos;
    }

    public String exchange(InetSocketAddress address, String message) throws Exception {
        try (SocketChannel channel = SocketChannel.open()) {
            channel.configureBlocking(false);
            channel.connect(address);

            long deadline = System.nanoTime() + timeoutNanos;
            awaitConnected(channel, deadline);
            writeFully(channel, message + "\n", deadline);
            return readLine(channel, deadline);
        }
    }

    private void awaitConnected(SocketChannel channel, long deadline) throws IOException {
        while (true) {
            if (channel.finishConnect()) {
                return;
            }
            pauseIfNeeded(deadline, "finish connecting");
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
                pauseIfNeeded(deadline, "read a response line");
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
                pauseIfNeeded(deadline, "write a request line");
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
