package yier.bubu.jdk.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal Boss/Worker Reactor example:
 * <ul>
 *   <li>Boss thread: accepts connections (OP_ACCEPT)</li>
 *   <li>Worker thread(s): handle read/write (OP_READ/OP_WRITE)</li>
 * </ul>
 *
 * <p>Usage (from project root):</p>
 * <pre>
 * mvn -pl jdk -DskipTests package
 * java -cp jdk/target/classes yier.bubu.jdk.nio.NioReactorEchoServer 8080 2
 * </pre>
 */
public class NioReactorEchoServer {
    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_WORKERS = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final int READ_BUFFER_SIZE = 4096;

    public static void main(String[] args) throws Exception {
        int port = args.length >= 1 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        int workerCount = args.length >= 2 ? Integer.parseInt(args[1]) : DEFAULT_WORKERS;
        if (workerCount <= 0) {
            throw new IllegalArgumentException("workerCount must be > 0");
        }

        WorkerReactor[] workers = new WorkerReactor[workerCount];
        for (int i = 0; i < workerCount; i++) {
            workers[i] = new WorkerReactor("nio-worker-" + i);
            workers[i].start();
        }

        BossReactor boss = new BossReactor(port, workers);
        Thread bossThread = new Thread(boss, "nio-boss");
        bossThread.start();

        System.out.println("Echo server started on port " + port + " with " + workerCount + " worker(s)");
        System.out.println("Try:  telnet 127.0.0.1 " + port + "   (or: nc 127.0.0.1 " + port + ")");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                boss.close();
            } catch (IOException ignored) {
            }
            for (WorkerReactor worker : workers) {
                try {
                    worker.close();
                } catch (IOException ignored) {
                }
            }
        }, "shutdown-hook"));

        bossThread.join();
    }

    private static final class BossReactor implements Runnable, AutoCloseable {
        private final WorkerReactor[] workers;
        private final AtomicInteger nextWorker = new AtomicInteger(0);

        private final Selector selector;
        private final ServerSocketChannel server;

        BossReactor(int port, WorkerReactor[] workers) throws IOException {
            this.workers = Objects.requireNonNull(workers, "workers");

            this.selector = Selector.open();
            this.server = ServerSocketChannel.open();
            this.server.configureBlocking(false);
            this.server.bind(new InetSocketAddress(port));
            this.server.register(selector, SelectionKey.OP_ACCEPT);
        }

        @Override
        public void run() {
            try {
                while (server.isOpen()) {
                    selector.select();

                    Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                    while (it.hasNext()) {
                        SelectionKey key = it.next();
                        it.remove();

                        if (!key.isValid()) {
                            continue;
                        }

                        if (key.isAcceptable()) {
                            handleAccept();
                        }
                    }
                }
            } catch (IOException e) {
                // Exit the boss loop on IO failures.
            } finally {
                try {
                    close();
                } catch (IOException ignored) {
                }
            }
        }

        private void handleAccept() throws IOException {
            while (true) {
                SocketChannel client = server.accept();
                if (client == null) {
                    return;
                }

                client.configureBlocking(false);

                WorkerReactor worker = workers[Math.floorMod(nextWorker.getAndIncrement(), workers.length)];
                worker.register(client);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                server.close();
            } finally {
                selector.wakeup();
                selector.close();
            }
        }
    }

    private static final class WorkerReactor implements Runnable, AutoCloseable {
        private final Selector selector;
        private final ConcurrentLinkedQueue<Runnable> pendingTasks = new ConcurrentLinkedQueue<>();
        private final Thread thread;

        WorkerReactor(String name) throws IOException {
            this.selector = Selector.open();
            this.thread = new Thread(this, Objects.requireNonNull(name, "name"));
        }

        void start() {
            thread.start();
        }

        void register(SocketChannel channel) {
            pendingTasks.add(() -> {
                try {
                    ConnectionContext ctx = new ConnectionContext();
                    channel.register(selector, SelectionKey.OP_READ, ctx);
                } catch (IOException e) {
                    try {
                        channel.close();
                    } catch (IOException ignored) {
                    }
                }
            });
            selector.wakeup();
        }

        @Override
        public void run() {
            try {
                while (selector.isOpen()) {
                    selector.select();

                    runPendingTasks();

                    Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                    while (it.hasNext()) {
                        SelectionKey key = it.next();
                        it.remove();

                        if (!key.isValid()) {
                            closeKey(key);
                            continue;
                        }

                        try {
                            if (key.isReadable()) {
                                handleRead(key);
                            }
                            if (key.isWritable()) {
                                handleWrite(key);
                            }
                        } catch (IOException e) {
                            closeKey(key);
                        }
                    }
                }
            } catch (IOException e) {
                // Exit the worker loop on IO failures.
            } finally {
                try {
                    close();
                } catch (IOException ignored) {
                }
            }
        }

        private void runPendingTasks() {
            while (true) {
                Runnable task = pendingTasks.poll();
                if (task == null) {
                    return;
                }
                task.run();
            }
        }

        private void handleRead(SelectionKey key) throws IOException {
            SocketChannel channel = (SocketChannel) key.channel();
            ConnectionContext ctx = (ConnectionContext) key.attachment();

            ByteBuffer readBuffer = ctx.readBuffer;
            readBuffer.clear();

            int n = channel.read(readBuffer);
            if (n == -1) {
                closeKey(key);
                return;
            }
            if (n == 0) {
                return;
            }

            readBuffer.flip();
            ByteBuffer outbound = ByteBuffer.allocate(readBuffer.remaining());
            outbound.put(readBuffer);
            outbound.flip();

            ctx.writeQueue.add(outbound);
            handleWrite(key);
        }

        private void handleWrite(SelectionKey key) throws IOException {
            SocketChannel channel = (SocketChannel) key.channel();
            ConnectionContext ctx = (ConnectionContext) key.attachment();

            while (true) {
                ByteBuffer buf = ctx.writeQueue.peek();
                if (buf == null) {
                    int ops = key.interestOps();
                    if ((ops & SelectionKey.OP_WRITE) != 0) {
                        key.interestOps(ops & ~SelectionKey.OP_WRITE);
                    }
                    return;
                }

                int n = channel.write(buf);
                if (n == 0 && buf.hasRemaining()) {
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                    return;
                }

                if (buf.hasRemaining()) {
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                    return;
                }

                ctx.writeQueue.poll();
            }
        }

        private void closeKey(SelectionKey key) {
            try {
                key.cancel();
            } catch (Exception ignored) {
            }
            try {
                key.channel().close();
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() throws IOException {
            selector.wakeup();
            selector.close();
        }
    }

    private static final class ConnectionContext {
        private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(READ_BUFFER_SIZE);
        private final ArrayDeque<ByteBuffer> writeQueue = new ArrayDeque<>();
    }
}
