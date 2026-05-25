package yier.bubu.jvm;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

final class DirectMemoryDemo {
    private DirectMemoryDemo() {
    }

    static void run(String[] args) throws Exception {
        Config config = Config.from(args);

        long totalBytes = config.totalMb * 1024L * 1024L;
        long chunkBytesLong = config.chunkMb * 1024L * 1024L;
        if (chunkBytesLong <= 0 || chunkBytesLong > Integer.MAX_VALUE) {
            System.out.println("Invalid args: --chunkMb must fit in a positive int-sized direct buffer");
            return;
        }

        int chunks = (int) ((totalBytes + chunkBytesLong - 1L) / chunkBytesLong);

        System.out.println("[DirectMemoryDemo]");
        System.out.println("Allocating direct buffers: totalMb=" + config.totalMb
                + " chunkMb=" + config.chunkMb
                + " chunks=" + chunks
                + " touch=" + config.touch);
        System.out.println("Tip: set a small limit to observe OOM quickly: -XX:MaxDirectMemorySize=64m");
        System.out.println();

        List<ByteBuffer> buffers = new ArrayList<ByteBuffer>(chunks);
        long allocatedBytes = 0L;
        for (int i = 0; i < chunks; i++) {
            long remainingBytes = totalBytes - allocatedBytes;
            int bytesToAllocate = (int) Math.min(chunkBytesLong, remainingBytes);
            ByteBuffer buf = ByteBuffer.allocateDirect(bytesToAllocate);
            if (config.touch) {
                touchEachPage(buf);
            }
            buffers.add(buf);
            allocatedBytes += bytesToAllocate;

            if ((i + 1) % config.reportEvery == 0 || i == chunks - 1) {
                System.out.println("allocatedBuffers=" + (i + 1)
                        + " approxAllocated=" + MemoryInspector.formatBytes(allocatedBytes));
                MemoryInspector.printBufferPools();
                System.out.println();
            }
        }

        System.out.println("Holding references to prevent GC from freeing direct buffers. buffers.size=" + buffers.size());
        if (config.sleepSeconds > 0) {
            System.out.println("Sleeping " + config.sleepSeconds + "s (attach tools like jcmd/jconsole if you want).");
            Thread.sleep(config.sleepSeconds * 1000L);
        }
    }

    static final class Config {
        final int totalMb;
        final int chunkMb;
        final int reportEvery;
        final boolean touch;
        final int sleepSeconds;

        private Config(int totalMb, int chunkMb, int reportEvery, boolean touch, int sleepSeconds) {
            this.totalMb = totalMb;
            this.chunkMb = chunkMb;
            this.reportEvery = reportEvery;
            this.touch = touch;
            this.sleepSeconds = sleepSeconds;
        }

        static Config from(String[] args) {
            return new Config(
                    Math.max(1, CliArgs.getInt(args, "--mb", 64)),
                    Math.max(1, CliArgs.getInt(args, "--chunkMb", 4)),
                    Math.max(1, CliArgs.getInt(args, "--reportEvery", 8)),
                    CliArgs.getBoolean(args, "--touch", true),
                    Math.max(0, CliArgs.getInt(args, "--sleepSeconds", 0))
            );
        }
    }

    private static void touchEachPage(ByteBuffer buf) {
        int page = 4096;
        for (int i = 0; i < buf.capacity(); i += page) {
            buf.put(i, (byte) 1);
        }
    }
}
