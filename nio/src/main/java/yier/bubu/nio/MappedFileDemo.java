package yier.bubu.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

public final class MappedFileDemo {
    private MappedFileDemo() {
    }

    public static void writeText(Path path, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(path, CREATE, READ, WRITE, TRUNCATE_EXISTING)) {
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0, bytes.length);
            mapped.put(bytes);
            mapped.force();
        }
    }

    public static String readText(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, READ)) {
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            ByteBuffer bytes = ByteBuffer.allocate((int) channel.size());
            bytes.put(mapped);
            bytes.flip();
            return StandardCharsets.UTF_8.decode(bytes).toString();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java yier.bubu.nio.MappedFileDemo <file> <text>");
            return;
        }

        Path path = java.nio.file.Paths.get(args[0]);
        writeText(path, args[1]);
        System.out.println(readText(path));
    }
}
