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
 * 一个只做“一次请求、一次响应”的非阻塞 Echo 客户端示例。
 *
 * <p>这个类同样刻意不使用 {@link java.nio.channels.Selector}，而是直接展示
 * {@code connect()} / {@code finishConnect()} / 非阻塞读写的行为。这样读代码时，
 * 注意力会集中在 Socket 本身的非阻塞语义，而不是更高层的网络框架或 Reactor 结构。</p>
 */
public final class NonBlockingEchoClient {
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final int READ_BUFFER_SIZE = 4;
    private final long timeoutNanos;

    public NonBlockingEchoClient() {
        this(TIMEOUT_NANOS);
    }

    // 这个包可见构造器主要给测试使用，方便把超时压短，稳定验证 timeout 契约。
    NonBlockingEchoClient(long timeoutNanos) {
        this.timeoutNanos = timeoutNanos;
    }

    public String exchange(InetSocketAddress address, String message) throws Exception {
        try (SocketChannel channel = SocketChannel.open()) {
            channel.configureBlocking(false);
            channel.connect(address);

            // 非阻塞 connect 不保证一步完成，真正建立连接要靠 finishConnect 轮询推进。
            long deadline = System.nanoTime() + timeoutNanos;
            awaitConnected(channel, deadline);
            writeFully(channel, message + "\n", deadline);
            return readLine(channel, deadline);
        }
    }

    private void awaitConnected(SocketChannel channel, long deadline) throws IOException {
        // 只要连接还没完成，就在截止时间内继续轮询 finishConnect。
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

        // 这里和服务端一样：read()==0 表示暂时没数据，只有读到换行才算一条完整响应。
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
        // 非阻塞写入需要处理“本次只写了一部分”的情况，因此要循环直到缓冲区清空。
        ByteBuffer buffer = ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer);
            if (written == 0) {
                pauseIfNeeded(deadline, "write a request line");
            }
        }
    }

    private void pauseIfNeeded(long deadline, String action) {
        // 统一处理“暂时没有进展”的退避逻辑：既限制总等待时间，也避免忙等。
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
            throw new IllegalStateException("timed out waiting to " + action);
        }
        LockSupport.parkNanos(Math.min(PARK_NANOS, remaining));
    }
}
