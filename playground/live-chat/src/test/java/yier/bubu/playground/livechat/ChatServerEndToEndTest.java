package yier.bubu.playground.livechat;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class ChatServerEndToEndTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final long MAX_WAIT_SECONDS = 5;

    private ChatServer server;

    @Before
    public void setUp() throws InterruptedException {
        server = new ChatServer(0, TIMEOUT);
        server.start();
    }

    @After
    public void tearDown() {
        server.stop();
    }

    @Test
    public void server_shouldChatAndCloseIdleSession() throws Exception {
        HttpClient httpClient = HttpClient.newHttpClient();

        HttpResponse<String> page = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
        Assert.assertEquals(200, page.statusCode());
        Assert.assertTrue(page.body().contains("在线客服"));

        CollectingListener listener = new CollectingListener();
        WebSocket webSocket = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + server.port() + "/ws"), listener)
                .join();

        ChatMessage welcome = listener.takeMessage();
        Assert.assertNotNull(welcome);
        Assert.assertEquals(ChatMessage.Type.CHAT, welcome.type());
        Assert.assertEquals(new BotResponder().welcome(), welcome.content());

        webSocket.sendText("{\"type\":\"chat\",\"content\":\"你好\"}", true).join();
        ChatMessage echo = listener.takeMessage();
        Assert.assertNotNull(echo);
        Assert.assertEquals(ChatMessage.Type.CHAT, echo.type());
        Assert.assertEquals("收到：「你好」", echo.content());

        ChatMessage closed = listener.takeMessage();
        Assert.assertNotNull(closed);
        Assert.assertEquals(ChatMessage.Type.CLOSED, closed.type());
        Assert.assertEquals("会话已超时关闭", closed.content());

        Assert.assertTrue(listener.awaitClosed());
    }

    private static final class CollectingListener implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final CountDownLatch closed = new CountDownLatch(1);
        private final StringBuilder pending = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            pending.append(data);
            if (last) {
                messages.offer(pending.toString());
                pending.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.countDown();
        }

        ChatMessage takeMessage() throws InterruptedException {
            String text = messages.poll(MAX_WAIT_SECONDS, TimeUnit.SECONDS);
            return text == null ? null : ChatMessage.parse(text);
        }

        boolean awaitClosed() throws InterruptedException {
            return closed.await(MAX_WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
