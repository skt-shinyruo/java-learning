package yier.bubu.jvm;

import java.util.ArrayList;
import java.util.List;

final class HeapOomDemo {
    private HeapOomDemo() {
    }

    static void run(String[] args) throws Exception {
        Config config = Config.from(args);
        if (config.totalMb <= 0 || config.chunkMb <= 0) {
            System.out.println("Invalid args: --mb and --chunkMb must be > 0");
            return;
        }

        long totalBytes = config.totalMb * 1024L * 1024L;
        long chunkBytesLong = config.chunkMb * 1024L * 1024L;
        if (chunkBytesLong <= 0 || chunkBytesLong > Integer.MAX_VALUE) {
            System.out.println("Invalid args: --chunkMb must fit in a positive int-sized byte array");
            return;
        }

        int chunkBytes = (int) chunkBytesLong;
        int chunks = (int) ((totalBytes + chunkBytesLong - 1L) / chunkBytesLong);

        System.out.println("[HeapOomDemo]");
        System.out.println("Allocating heap byte arrays and holding references.");
        System.out.println("totalMb=" + config.totalMb + " chunkMb=" + config.chunkMb + " chunks=" + chunks);
        System.out.println("Tip: run with -Xms64m -Xmx64m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=target/jvm-lab-heap.hprof");
        System.out.println();

        List<byte[]> retained = new ArrayList<byte[]>(chunks);
        long allocatedBytes = 0L;
        for (int i = 0; i < chunks; i++) {
            long remainingBytes = totalBytes - allocatedBytes;
            int bytesToAllocate = (int) Math.min(chunkBytesLong, remainingBytes);
            byte[] block = new byte[bytesToAllocate];
            block[0] = (byte) i;
            retained.add(block);
            allocatedBytes += bytesToAllocate;

            if ((i + 1) % config.reportEvery == 0 || i == chunks - 1) {
                System.out.println("allocatedChunks=" + (i + 1)
                        + " approxAllocated=" + MemoryInspector.formatBytes(allocatedBytes));
                MemoryInspector.printMemorySummary();
                System.out.println();
            }
        }

        System.out.println("Holding references to prevent GC from freeing heap arrays. retained.size=" + retained.size()
                + " allocated=" + MemoryInspector.formatBytes(allocatedBytes));
        if (config.sleepSeconds > 0) {
            System.out.println("Sleeping " + config.sleepSeconds + "s (attach tools like jcmd/jmap if you want).");
            Thread.sleep(config.sleepSeconds * 1000L);
        }
    }

    static final class Config {
        final int totalMb;
        final int chunkMb;
        final int reportEvery;
        final int sleepSeconds;

        private Config(int totalMb, int chunkMb, int reportEvery, int sleepSeconds) {
            this.totalMb = totalMb;
            this.chunkMb = chunkMb;
            this.reportEvery = reportEvery;
            this.sleepSeconds = sleepSeconds;
        }

        static Config from(String[] args) {
            return new Config(
                    CliArgs.getInt(args, "--mb", 96),
                    CliArgs.getInt(args, "--chunkMb", 1),
                    Math.max(1, CliArgs.getInt(args, "--reportEvery", 16)),
                    CliArgs.getInt(args, "--sleepSeconds", 0)
            );
        }
    }
}
