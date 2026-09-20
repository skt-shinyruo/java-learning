package yier.bubu.playground.livechat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class HttpStaticPageHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final byte[] INDEX_HTML = readResource("/chat/index.html");

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (request.method() != HttpMethod.GET) {
            respond(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED,
                    "Method Not Allowed".getBytes(StandardCharsets.UTF_8), "text/plain; charset=UTF-8");
            return;
        }
        String uri = request.uri();
        if ("/".equals(uri) || "/index.html".equals(uri)) {
            respond(ctx, request, HttpResponseStatus.OK, INDEX_HTML, "text/html; charset=UTF-8");
            return;
        }
        respond(ctx, request, HttpResponseStatus.NOT_FOUND,
                "Not Found".getBytes(StandardCharsets.UTF_8), "text/plain; charset=UTF-8");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }

    private void respond(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status,
                         byte[] body, String contentType) {
        ByteBuf content = Unpooled.wrappedBuffer(body);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        if (HttpUtil.isKeepAlive(request)) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static byte[] readResource(String path) {
        try (InputStream in = HttpStaticPageHandler.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("classpath resource not found: " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read classpath resource: " + path, e);
        }
    }
}
