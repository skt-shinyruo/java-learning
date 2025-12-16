package yier.bubu.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.CharsetUtil;
import org.junit.Assert;
import org.junit.Test;

public class UppercaseInboundHandlerTest {
    @Test
    public void handler_shouldUppercaseInboundByteBuf() {
        EmbeddedChannel channel = new EmbeddedChannel(new UppercaseInboundHandler());

        ByteBuf inbound = Unpooled.copiedBuffer("hello", CharsetUtil.UTF_8);
        channel.writeInbound(inbound);
        channel.finish();

        ByteBuf outbound = channel.readOutbound();
        Assert.assertNotNull(outbound);
        try {
            Assert.assertEquals("HELLO", outbound.toString(CharsetUtil.UTF_8));
        } finally {
            outbound.release();
        }
    }
}

