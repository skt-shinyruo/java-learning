package yier.bubu.jdk.nio.nonblocking;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * 一个只处理“单次连接、单行请求”的非阻塞 Echo 服务端示例。
 *
 * <p>这个类刻意不使用 {@link java.nio.channels.Selector}，重点只放在
 * {@link ServerSocketChannel} / {@link SocketChannel} 的非阻塞语义本身：
 * 没有连接时 {@code accept()} 会返回 {@code null}，读写也可能暂时没有进展。
 * 它和 {@code NioReactorEchoServer} 的区别在于，这里不讲 Reactor 模式，只讲最底层的轮询行为。</p>
 */
public final class NonBlockingEchoServer {
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final int READ_BUFFER_SIZE = 4;
    private final ServerSocketChannel server;
    private final InetSocketAddress address;

    public NonBlockingEchoServer() throws IOException {
        this.server = ServerSocketChannel.open();
        // 在构造阶段就完成绑定，这样调用方可以直接拿到端口，而不需要额外的同步协议。
        this.server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        this.server.configureBlocking(false);
        this.address = new InetSocketAddress(InetAddress.getLoopbackAddress(), server.socket().getLocalPort());
    }

    public int port() {
        return address.getPort();
    }

    public InetSocketAddress address() {
        return address;
    }

    public void serveOnce() throws Exception {
        try {
            // 这个示例只处理一个客户端，所以在截止时间内等待一次 accept 即可。
            long deadline = System.nanoTime() + TIMEOUT_NANOS;
            try (SocketChannel client = awaitAccepted(server, deadline)) {
                client.configureBlocking(false);
                String request = readLine(client, deadline);
                writeFully(client, "ACK:" + request + "\n", deadline);
            }
        } finally {
            server.close();
        }
    }

    private SocketChannel awaitAccepted(ServerSocketChannel server, long deadline) throws IOException {
        // 非阻塞 accept 在“现在还没有连接”时返回 null，而不是阻塞住线程。
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

        // 非阻塞 read 返回 0 只表示“当前没有新数据”，所以要持续累计直到遇到换行符。
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
        // 非阻塞 write 可能一次写不完，必须一直推进 ByteBuffer 到完全耗尽。
        ByteBuffer buffer = ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer);
            if (written == 0) {
                pauseIfNeeded(deadline, "write a response line");
            }
        }
    }

    private void pauseIfNeeded(long deadline, String action) {
        // deadline 防止无限等待；短暂 park 则避免在“暂时没进展”时空转烧 CPU。
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
            throw new IllegalStateException("timed out waiting to " + action);
        }
        LockSupport.parkNanos(Math.min(PARK_NANOS, remaining));
    }
}
