package yier.bubu.nio;

import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.List;

public class DirectBufferCopyPathDemoTest {
    @Test
    public void allocateHeapBuffer_shouldCreateHeapBackedBuffer() {
        ByteBuffer buffer = DirectBufferCopyPathDemo.allocateHeapBuffer(16);

        Assert.assertFalse(buffer.isDirect());
        Assert.assertTrue(buffer.hasArray());
        Assert.assertEquals(16, buffer.capacity());
    }

    @Test
    public void allocateDirectBuffer_shouldCreateOffHeapBuffer() {
        ByteBuffer buffer = DirectBufferCopyPathDemo.allocateDirectBuffer(16);

        Assert.assertTrue(buffer.isDirect());
        Assert.assertFalse(buffer.hasArray());
        Assert.assertEquals(16, buffer.capacity());
    }

    @Test
    public void copyPathNotes_shouldExplainReducedCopy() {
        List<String> notes = DirectBufferCopyPathDemo.copyPathNotes();

        Assert.assertTrue(notes.contains("heap buffer: Java heap byte[] -> temporary direct/native buffer -> kernel buffer"));
        Assert.assertTrue(notes.contains("direct buffer: direct/native buffer -> kernel buffer"));
        Assert.assertTrue(notes.contains("reduced copy: Java heap byte[] <-> temporary direct/native buffer"));
    }
}
