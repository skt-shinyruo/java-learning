package yier.bubu.nio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class EchoIoModelDemo {
    private static final String REQUEST = "ping";
    private static final int TIMEOUT_SECONDS = 5;

    private EchoIoModelDemo() {
    }

    public static EchoResult run(String mode) throws Exception {
        if ("sync-blocking".equals(mode)) {
            return runSyncBlocking();
        }
        if ("sync-nonblocking".equals(mode)) {
            return runSyncNonBlocking();
        }
        if ("async-blocking".equals(mode)) {
            return runAsyncBlocking();
        }
        if ("async-nonblocking".equals(mode)) {
            return runAsyncNonBlocking();
        }
        throw new IllegalArgumentException("Unknown echo mode: " + mode);
    }

    public static EchoResult runSyncBlocking() throws Exception {
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final int port = serverSocket.getLocalPort();
            Future<Void> server = executor.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    try (Socket accepted = serverSocket.accept()) {
                        events.add("server: accept");
                        InputStream in = accepted.getInputStream();
                        OutputStream out = accepted.getOutputStream();
                        String request = readAvailableMessage(in);
                        events.add("server: read " + request);
                        out.write(request.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        events.add("server: echo " + request);
                    }
                    return null;
                }
            });

            String response;
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                events.add("client: connect");
                client.getOutputStream().write(REQUEST.getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                client.shutdownOutput();
                events.add("client: write " + REQUEST);
                events.add("client: blocking read waits for echo");
                response = readAvailableMessage(client.getInputStream());
                events.add("client: read " + response);
            }

            waitFor(server);
            return result("sync-blocking", response, events);
        } finally {
            executor.shutdownNow();
        }
    }

    public static EchoResult runSyncNonBlocking() throws IOException {
        List<String> events = new ArrayList<String>();
        ByteBuffer clientWrite = ByteBuffer.wrap(REQUEST.getBytes(StandardCharsets.UTF_8));
        ByteBuffer clientRead = ByteBuffer.allocate(64);
        ByteBuffer serverRead = ByteBuffer.allocate(64);
        String response = null;
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);

        try (Selector selector = Selector.open();
             ServerSocketChannel server = ServerSocketChannel.open();
             SocketChannel client = SocketChannel.open()) {
            server.configureBlocking(false);
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            server.register(selector, SelectionKey.OP_ACCEPT);

            client.configureBlocking(false);
            boolean connected = client.connect(server.getLocalAddress());
            if (connected) {
                client.register(selector, SelectionKey.OP_WRITE);
                events.add("client: selector reports connect ready");
            } else {
                client.register(selector, SelectionKey.OP_CONNECT);
                events.add("client: connect started");
            }

            while (response == null && System.currentTimeMillis() < deadline) {
                int selected = selector.select(200);
                if (selected == 0) {
                    events.add("client: selector timeout, do other work");
                    continue;
                }

                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = keys.iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();

                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
                        SocketChannel accepted = serverChannel.accept();
                        accepted.configureBlocking(false);
                        accepted.register(selector, SelectionKey.OP_READ);
                        events.add("server: accept");
                    } else if (key.isConnectable()) {
                        SocketChannel channel = (SocketChannel) key.channel();
                        if (channel.finishConnect()) {
                            events.add("client: selector reports connect ready");
                            key.interestOps(SelectionKey.OP_WRITE);
                        }
                    } else if (key.isWritable()) {
                        SocketChannel channel = (SocketChannel) key.channel();
                        channel.write(clientWrite);
                        if (!clientWrite.hasRemaining()) {
                            events.add("client: write " + REQUEST);
                            key.interestOps(SelectionKey.OP_READ);
                        }
                    } else if (key.isReadable()) {
                        SocketChannel channel = (SocketChannel) key.channel();
                        if (channel == client) {
                            channel.read(clientRead);
                            clientRead.flip();
                            response = decode(clientRead);
                            events.add("client: selector reports read ready");
                            events.add("client: read " + response);
                            key.cancel();
                        } else {
                            channel.read(serverRead);
                            serverRead.flip();
                            String request = decode(serverRead);
                            events.add("server: read " + request);
                            ByteBuffer responseBuffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));
                            while (responseBuffer.hasRemaining()) {
                                channel.write(responseBuffer);
                            }
                            events.add("server: echo " + request);
                            channel.close();
                        }
                    }
                }
            }
        }

        if (response == null) {
            throw new IOException("Timed out waiting for sync-nonblocking echo");
        }
        return result("sync-nonblocking", response, events);
    }

    public static EchoResult runAsyncBlocking() throws Exception {
        List<String> events = new ArrayList<String>();
        ByteBuffer clientRead = ByteBuffer.allocate(64);
        ByteBuffer serverRead = ByteBuffer.allocate(64);

        try (AsynchronousServerSocketChannel server = AsynchronousServerSocketChannel.open();
             AsynchronousSocketChannel client = AsynchronousSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            Future<AsynchronousSocketChannel> acceptedFuture = server.accept();
            client.connect(server.getLocalAddress()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            events.add("client: Future.get waits for connect");

            try (AsynchronousSocketChannel accepted = acceptedFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                events.add("server: accept");
                client.write(ByteBuffer.wrap(REQUEST.getBytes(StandardCharsets.UTF_8))).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                events.add("client: Future.get waits for write");
                accepted.read(serverRead).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                serverRead.flip();
                String request = decode(serverRead);
                events.add("server: read " + request);
                accepted.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8))).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                events.add("server: echo " + request);
                events.add("client: Future.get waits for read");
                client.read(clientRead).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }

        clientRead.flip();
        String response = decode(clientRead);
        events.add("client: read " + response);
        return result("async-blocking", response, events);
    }

    public static EchoResult runAsyncNonBlocking() throws Exception {
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> response = new AtomicReference<String>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final AtomicReference<AsynchronousSocketChannel> acceptedRef = new AtomicReference<AsynchronousSocketChannel>();
        final AtomicReference<AsynchronousSocketChannel> clientRef = new AtomicReference<AsynchronousSocketChannel>();

        try (final AsynchronousServerSocketChannel server = AsynchronousServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            server.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
                @Override
                public void completed(final AsynchronousSocketChannel accepted, Void attachment) {
                    acceptedRef.set(accepted);
                    events.add("server: accept");
                    final ByteBuffer serverRead = ByteBuffer.allocate(64);
                    accepted.read(serverRead, null, new CompletionHandler<Integer, Void>() {
                        @Override
                        public void completed(Integer read, Void attachment) {
                            try {
                                serverRead.flip();
                                final String request = decode(serverRead);
                                events.add("server: read " + request);
                                accepted.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)), null,
                                        new CompletionHandler<Integer, Void>() {
                                            @Override
                                            public void completed(Integer written, Void attachment) {
                                                events.add("server: echo " + request);
                                                closeQuietly(accepted);
                                            }

                                            @Override
                                            public void failed(Throwable exc, Void attachment) {
                                                failure.set(exc);
                                                closeQuietly(accepted);
                                                done.countDown();
                                            }
                                        });
                            } catch (Throwable exc) {
                                failure.set(exc);
                                closeQuietly(accepted);
                                done.countDown();
                            }
                        }

                        @Override
                        public void failed(Throwable exc, Void attachment) {
                            failure.set(exc);
                            closeQuietly(accepted);
                            done.countDown();
                        }
                    });
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    failure.set(exc);
                    done.countDown();
                }
            });

            final AsynchronousSocketChannel client = AsynchronousSocketChannel.open();
            clientRef.set(client);
            client.connect(server.getLocalAddress(), null, new CompletionHandler<Void, Void>() {
                @Override
                public void completed(Void result, Void attachment) {
                    events.add("client: CompletionHandler connect");
                    client.write(ByteBuffer.wrap(REQUEST.getBytes(StandardCharsets.UTF_8)), null,
                            new CompletionHandler<Integer, Void>() {
                                @Override
                                public void completed(Integer written, Void attachment) {
                                    events.add("client: CompletionHandler write " + REQUEST);
                                    final ByteBuffer clientRead = ByteBuffer.allocate(64);
                                    client.read(clientRead, null, new CompletionHandler<Integer, Void>() {
                                        @Override
                                        public void completed(Integer read, Void attachment) {
                                            clientRead.flip();
                                            String text = decode(clientRead);
                                            response.set(text);
                                            events.add("client: CompletionHandler read " + text);
                                            closeQuietly(client);
                                            done.countDown();
                                        }

                                        @Override
                                        public void failed(Throwable exc, Void attachment) {
                                            failure.set(exc);
                                            closeQuietly(client);
                                            done.countDown();
                                        }
                                    });
                                }

                                @Override
                                public void failed(Throwable exc, Void attachment) {
                                    failure.set(exc);
                                    closeQuietly(client);
                                    done.countDown();
                                }
                            });
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    failure.set(exc);
                    closeQuietly(client);
                    done.countDown();
                }
            });

            if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for async-nonblocking echo");
            }
            if (failure.get() != null) {
                throw new IOException("Async echo failed", failure.get());
            }
            return result("async-nonblocking", response.get(), events);
        } finally {
            closeQuietly(clientRef.get());
            closeQuietly(acceptedRef.get());
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "sync-blocking" : args[0];
        print(run(mode));
    }

    public static void print(EchoResult result) {
        System.out.println("[EchoIoModelDemo]");
        System.out.println("mode=" + result.mode());
        System.out.println("request=" + result.request());
        System.out.println("response=" + result.response());
        for (String event : result.events()) {
            System.out.println(event);
        }
    }

    private static EchoResult result(String mode, String response, List<String> events) {
        return new EchoResult(mode, REQUEST, response, copy(events));
    }

    private static List<String> copy(List<String> events) {
        synchronized (events) {
            return new ArrayList<String>(events);
        }
    }

    private static void waitFor(Future<Void> future) throws Exception {
        try {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    private static String readAvailableMessage(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String decode(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void closeQuietly(AsynchronousSocketChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static final class EchoResult {
        private final String mode;
        private final String request;
        private final String response;
        private final List<String> events;

        private EchoResult(String mode, String request, String response, List<String> events) {
            this.mode = mode;
            this.request = request;
            this.response = response;
            this.events = Collections.unmodifiableList(events);
        }

        public String mode() {
            return mode;
        }

        public String request() {
            return request;
        }

        public String response() {
            return response;
        }

        public List<String> events() {
            return events;
        }
    }
}
