package yier.bubu.nio;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

public final class DirectBufferCopyPathDemo {
    private DirectBufferCopyPathDemo() {
    }

    public static ByteBuffer allocateHeapBuffer(int capacity) {
        return ByteBuffer.allocate(capacity);
    }

    public static ByteBuffer allocateDirectBuffer(int capacity) {
        return ByteBuffer.allocateDirect(capacity);
    }

    public static List<String> copyPathNotes() {
        return Arrays.asList(
                "heap buffer: Java heap byte[] -> temporary direct/native buffer -> kernel buffer",
                "direct buffer: direct/native buffer -> kernel buffer",
                "reduced copy: Java heap byte[] <-> temporary direct/native buffer"
        );
    }

    public static void main(String[] args) {
        int capacity = args.length > 0 ? Integer.parseInt(args[0]) : 1024;

        ByteBuffer heapBuffer = allocateHeapBuffer(capacity);
        ByteBuffer directBuffer = allocateDirectBuffer(capacity);

        System.out.println("[DirectBufferCopyPathDemo]");
        System.out.println("heapBuffer.isDirect=" + heapBuffer.isDirect()
                + ", hasArray=" + heapBuffer.hasArray()
                + ", capacity=" + heapBuffer.capacity());
        System.out.println("directBuffer.isDirect=" + directBuffer.isDirect()
                + ", hasArray=" + directBuffer.hasArray()
                + ", capacity=" + directBuffer.capacity());
        System.out.println();

        for (String note : copyPathNotes()) {
            System.out.println(note);
        }
    }
}
