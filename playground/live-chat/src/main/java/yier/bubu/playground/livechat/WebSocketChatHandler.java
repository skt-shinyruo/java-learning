package yier.bubu.playground.livechat;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

public final class WebSocketChatHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private final SessionTimeoutScheduler timeouts;
    private final BotResponder bot = new BotResponder();

    public WebSocketChatHandler(SessionTimeoutScheduler timeouts) {
        this.timeouts = timeouts;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            timeouts.start(ctx.channel(), () -> closeForTimeout(ctx));
            write(ctx, ChatMessage.chat(bot.welcome()));
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        ChatMessage message = ChatMessage.parse(frame.text());
        if (message == null || message.type() != ChatMessage.Type.CHAT) {
            return;
        }
        timeouts.touch(ctx.channel());
        write(ctx, ChatMessage.chat(bot.reply(message.content())));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        timeouts.cancel(ctx.channel());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }

    private void closeForTimeout(ChannelHandlerContext ctx) {
        ctx.writeAndFlush(new TextWebSocketFrame(ChatMessage.closed(bot.timeoutNotice()).toJson()))
                .addListener(future -> ctx.writeAndFlush(new CloseWebSocketFrame())
                        .addListener(ChannelFutureListener.CLOSE));
    }

    private void write(ChannelHandlerContext ctx, ChatMessage message) {
        ctx.channel().writeAndFlush(new TextWebSocketFrame(message.toJson()));
    }
}
