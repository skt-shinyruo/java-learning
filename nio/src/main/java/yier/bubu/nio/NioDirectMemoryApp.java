package yier.bubu.nio;

import java.nio.file.Files;
import java.nio.file.Path;

public final class NioDirectMemoryApp {
    private NioDirectMemoryApp() {
    }

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "help" : args[0];
        if ("copy-path".equals(command)) {
            String[] rest = withoutCommand(args);
            DirectBufferCopyPathDemo.main(rest);
            return;
        }
        if ("mmap".equals(command)) {
            runMmapDemo();
            return;
        }
        if ("echo".equals(command)) {
            String[] rest = withoutCommand(args);
            EchoIoModelDemo.main(rest);
            return;
        }
        printHelp();
    }

    private static String[] withoutCommand(String[] args) {
        String[] rest = new String[Math.max(0, args.length - 1)];
        if (rest.length > 0) {
            System.arraycopy(args, 1, rest, 0, rest.length);
        }
        return rest;
    }

    private static void runMmapDemo() throws Exception {
        Path file = Files.createTempFile("nio-direct-memory-app", ".txt");
        try {
            MappedFileDemo.writeText(file, "hello mmap");
            System.out.println("mapped file=" + file);
            System.out.println("content=" + MappedFileDemo.readText(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void printHelp() {
        System.out.println("Usage:");
        System.out.println("  java -cp nio/target/classes yier.bubu.nio.NioDirectMemoryApp <command>");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  copy-path [capacity]  Compare heap and direct ByteBuffer copy paths");
        System.out.println("  mmap                  Write/read a temp file with MappedByteBuffer");
        System.out.println("  echo [mode]           Run echo I/O model demo");
        System.out.println("                         modes: sync-blocking, sync-nonblocking, async-blocking, async-nonblocking");
        System.out.println("  help                  Show this help");
    }
}
