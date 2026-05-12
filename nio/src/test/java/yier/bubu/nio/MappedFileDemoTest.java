package yier.bubu.nio;

import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

public class MappedFileDemoTest {
    @Test
    public void writeAndRead_shouldRoundTripThroughMappedByteBuffer() throws Exception {
        Path tempFile = Files.createTempFile("nio-mapped-file-demo", ".txt");
        try {
            MappedFileDemo.writeText(tempFile, "hello mmap");

            Assert.assertEquals("hello mmap", MappedFileDemo.readText(tempFile));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
