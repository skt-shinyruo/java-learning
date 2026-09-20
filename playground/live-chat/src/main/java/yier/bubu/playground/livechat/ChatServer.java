package yier.bubu.playground.livechat;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.HashedWheelTimer;

import java.net.InetSocketAddress;
import java.time.Duration;

public final class ChatServer {

    public static final int DEFAULT_PORT = 8080;
    public static final Duration DEFAULT_SESSION_TIMEOUT = Duration.ofSeconds(10);

    private final int port;
    private final Duration sessionTimeout;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private HashedWheelTimer timer;
    private Channel serverChannel;

    public ChatServer(int port, Duration sessionTimeout) {
        this.port = port;
        this.sessionTimeout = sessionTimeout;
    }

    public Channel start() throws InterruptedException {
        timer = new HashedWheelTimer();
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        SessionTimeoutScheduler timeouts = new SessionTimeoutScheduler(timer, sessionTimeout);

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        ChannelPipeline pipeline = channel.pipeline();
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(64 * 1024));
                        pipeline.addLast(new WebSocketServerProtocolHandler("/ws"));
                        pipeline.addLast(new HttpStaticPageHandler());
                        pipeline.addLast(new WebSocketChatHandler(timeouts));
                    }
                });
        serverChannel = bootstrap.bind(port).sync().channel();
        return serverChannel;
    }

    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public void stop() {
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().awaitUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().awaitUninterruptibly();
        }
        if (timer != null) {
            timer.stop();
        }
    }

    public static void main(String[] args) throws Exception {
        ChatServer server = new ChatServer(DEFAULT_PORT, DEFAULT_SESSION_TIMEOUT);
        Channel channel = server.start();
        System.out.println("客服聊天 demo 已启动：http://localhost:" + server.port() + "/");
        System.out.println("客户静默超过 " + DEFAULT_SESSION_TIMEOUT.toSeconds() + " 秒后，服务端将主动关闭会话。");
        channel.closeFuture().sync();
        server.stop();
    }
}
