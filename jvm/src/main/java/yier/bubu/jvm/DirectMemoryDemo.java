package yier.bubu.jvm;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

final class DirectMemoryDemo {
    private DirectMemoryDemo() {
    }

    static void run(String[] args) throws Exception {
        int totalMb = CliArgs.getInt(args, "--mb", 64);
        int chunkMb = CliArgs.getInt(args, "--chunkMb", 4);
        int reportEvery = CliArgs.getInt(args, "--reportEvery", 8);
        boolean touch = CliArgs.getBoolean(args, "--touch", true);
        int sleepSeconds = CliArgs.getInt(args, "--sleepSeconds", 0);

        if (totalMb <= 0 || chunkMb <= 0) {
            System.out.println("Invalid args: --mb and --chunkMb must be > 0");
            return;
        }

        long totalBytes = totalMb * 1024L * 1024L;
        int chunkBytes = chunkMb * 1024 * 1024;
        int chunks = (int) (totalBytes / chunkBytes);
        if (chunks <= 0) {
            chunks = 1;
        }

        System.out.println("[DirectMemoryDemo]");
        System.out.println("Allocating direct buffers: totalMb=" + totalMb + " chunkMb=" + chunkMb + " chunks=" + chunks + " touch=" + touch);
        System.out.println("Tip: set a small limit to observe OOM quickly: -XX:MaxDirectMemorySize=64m");
        System.out.println();

        List<ByteBuffer> buffers = new ArrayList<>();
        for (int i = 0; i < chunks; i++) {
            ByteBuffer buf = ByteBuffer.allocateDirect(chunkBytes);
            if (touch) {
                touchEachPage(buf);
            }
            buffers.add(buf);

            if ((i + 1) % reportEvery == 0 || i == chunks - 1) {
                System.out.println("allocatedBuffers=" + (i + 1) + " approxAllocated=" + MemoryInspector.formatBytes((long) (i + 1) * chunkBytes));
                MemoryInspector.printBufferPools();
                System.out.println();
            }
        }

        System.out.println("Holding references to prevent GC from freeing direct buffers. buffers.size=" + buffers.size());
        if (sleepSeconds > 0) {
            System.out.println("Sleeping " + sleepSeconds + "s (attach tools like jcmd/jconsole if you want).");
            Thread.sleep(sleepSeconds * 1000L);
        }
    }

    private static void touchEachPage(ByteBuffer buf) {
        int page = 4096;
        for (int i = 0; i < buf.capacity(); i += page) {
            buf.put(i, (byte) 1);
        }
    }
}

