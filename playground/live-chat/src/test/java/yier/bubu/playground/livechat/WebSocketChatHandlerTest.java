package yier.bubu.playground.livechat;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.HashedWheelTimer;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.function.BooleanSupplier;

public class WebSocketChatHandlerTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);
    private static final long MAX_WAIT_MILLIS = 3000;

    private HashedWheelTimer timer;

    @Before
    public void setUp() {
        timer = new HashedWheelTimer();
    }

    @After
    public void tearDown() {
        timer.stop();
    }

    @Test
    public void handshake_shouldSendWelcomeMessage() {
        EmbeddedChannel channel = newSessionChannel();

        TextWebSocketFrame welcome = readFrameWithin(channel, MAX_WAIT_MILLIS);
        Assert.assertNotNull(welcome);
        ChatMessage message = ChatMessage.parse(welcome.text());
        Assert.assertEquals(ChatMessage.Type.CHAT, message.type());
        Assert.assertEquals(new BotResponder().welcome(), message.content());
        welcome.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void chatMessage_shouldReplyWithEcho() {
        EmbeddedChannel channel = newSessionChannel();
        readFrameWithin(channel, MAX_WAIT_MILLIS).release();

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"chat\",\"content\":\"我要退货\"}"));

        TextWebSocketFrame echo = readFrameWithin(channel, MAX_WAIT_MILLIS);
        Assert.assertNotNull(echo);
        ChatMessage message = ChatMessage.parse(echo.text());
        Assert.assertEquals(ChatMessage.Type.CHAT, message.type());
        Assert.assertEquals("收到：「我要退货」", message.content());
        echo.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void chatMessageWithEscapedCharacters_shouldRoundTripJson() {
        EmbeddedChannel channel = newSessionChannel();
        readFrameWithin(channel, MAX_WAIT_MILLIS).release();

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"chat\",\"content\":\"他说：\\\"OK\\\"\\n走了\"}"));

        TextWebSocketFrame echo = readFrameWithin(channel, MAX_WAIT_MILLIS);
        Assert.assertNotNull(echo);
        ChatMessage message = ChatMessage.parse(echo.text());
        Assert.assertEquals("收到：「他说：\"OK\"\n走了」", message.content());
        echo.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void idleSession_shouldSendClosedNoticeAndCloseChannel() {
        EmbeddedChannel channel = newSessionChannel();
        readFrameWithin(channel, MAX_WAIT_MILLIS).release();

        TextWebSocketFrame closed = readFrameWithin(channel, MAX_WAIT_MILLIS);
        Assert.assertNotNull(closed);
        ChatMessage message = ChatMessage.parse(closed.text());
        Assert.assertEquals(ChatMessage.Type.CLOSED, message.type());
        Assert.assertEquals("会话已超时关闭", message.content());
        closed.release();

        Assert.assertTrue(awaitWithin(() -> !channel.isOpen(), MAX_WAIT_MILLIS));
        channel.finishAndReleaseAll();
    }

    @Test
    public void chatMessage_shouldResetInactivityTimer() throws InterruptedException {
        EmbeddedChannel channel = newSessionChannel();
        readFrameWithin(channel, MAX_WAIT_MILLIS).release();

        Thread.sleep(TIMEOUT.toMillis() / 2);
        long sentAt = System.currentTimeMillis();
        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"chat\",\"content\":\"在吗\"}"));
        readFrameWithin(channel, MAX_WAIT_MILLIS).release();

        assertNoFrameWithin(channel, TIMEOUT.toMillis() / 2);

        TextWebSocketFrame closed = readFrameWithin(channel, MAX_WAIT_MILLIS);
        Assert.assertNotNull(closed);
        Assert.assertEquals(ChatMessage.Type.CLOSED, ChatMessage.parse(closed.text()).type());
        Assert.assertTrue(System.currentTimeMillis() - sentAt >= TIMEOUT.toMillis() - 50);
        closed.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void chatMessageNearDeadline_shouldKeepSessionOpen() throws InterruptedException {
        EmbeddedChannel channel = newSessionChannel();
        readFrameWithin(channel, MAX_WAIT_MILLIS).release();

        Thread.sleep(TIMEOUT.toMillis() * 5 / 6);
        long sentAt = System.currentTimeMillis();
        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"chat\",\"content\":\"再问一下\"}"));
        readFrameWithin(channel, MAX_WAIT_MILLIS).release();

        assertNoFrameWithin(channel, TIMEOUT.toMillis() / 3);

        TextWebSocketFrame closed = readFrameWithin(channel, MAX_WAIT_MILLIS);
        Assert.assertNotNull(closed);
        Assert.assertEquals(ChatMessage.Type.CLOSED, ChatMessage.parse(closed.text()).type());
        Assert.assertTrue(System.currentTimeMillis() - sentAt >= TIMEOUT.toMillis() - 50);
        closed.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void channelClose_shouldCancelPendingTimeoutTask() {
        EmbeddedChannel channel = newSessionChannel();
        readFrameWithin(channel, MAX_WAIT_MILLIS).release();

        channel.close();

        Assert.assertTrue(timer.stop().isEmpty());
    }

    private EmbeddedChannel newSessionChannel() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new WebSocketChatHandler(new SessionTimeoutScheduler(timer, TIMEOUT)));
        channel.pipeline().fireUserEventTriggered(
                new WebSocketServerProtocolHandler.HandshakeComplete("/ws", EmptyHttpHeaders.INSTANCE, null));
        return channel;
    }

    private static TextWebSocketFrame readFrameWithin(EmbeddedChannel channel, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            Object message = channel.readOutbound();
            if (message instanceof TextWebSocketFrame) {
                return (TextWebSocketFrame) message;
            }
            if (message != null) {
                ReferenceCountUtil.release(message);
            }
            sleepQuietly(10);
        }
        return null;
    }

    private static void assertNoFrameWithin(EmbeddedChannel channel, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            Object message = channel.readOutbound();
            if (message != null) {
                ReferenceCountUtil.release(message);
                Assert.fail("预期窗口内不应有出站消息，但收到了: " + message.getClass().getSimpleName());
            }
            sleepQuietly(10);
        }
    }

    private static boolean awaitWithin(BooleanSupplier condition, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleepQuietly(10);
        }
        return condition.getAsBoolean();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
