package yier.bubu.jvm;

import java.util.ArrayList;
import java.util.List;

final class StaticMemoryLeakDemo {
    private static final List<byte[]> RETAINED = new ArrayList<byte[]>();

    private StaticMemoryLeakDemo() {
    }

    static void run(String[] args) throws Exception {
        Config config = Config.from(args);
        long totalBytes = config.totalMb * 1024L * 1024L;
        long chunkBytesLong = config.chunkMb * 1024L * 1024L;
        if (chunkBytesLong <= 0 || chunkBytesLong > Integer.MAX_VALUE) {
            System.out.println("Invalid args: --chunkMb must fit in a positive int-sized byte array");
            return;
        }

        int chunkBytes = (int) chunkBytesLong;
        int chunks = (int) ((totalBytes + chunkBytesLong - 1L) / chunkBytesLong);

        System.out.println("[StaticMemoryLeakDemo]");
        System.out.println("Growing a static List<byte[]> to simulate heap retention.");
        System.out.println("totalMb=" + config.totalMb + " chunkMb=" + config.chunkMb + " chunks=" + chunks);
        System.out.println("Use jcmd <pid> GC.class_histogram and heap dump tools to observe byte[] retained by a static field.");
        System.out.println();

        long retainedBytes = 0L;
        for (int i = 0; i < chunks; i++) {
            long remainingBytes = totalBytes - retainedBytes;
            int bytesToAllocate = (int) Math.min(chunkBytesLong, remainingBytes);
            byte[] block = new byte[bytesToAllocate];
            block[0] = (byte) i;
            RETAINED.add(block);
            retainedBytes += bytesToAllocate;

            if ((i + 1) % config.reportEvery == 0 || i == chunks - 1) {
                System.out.println("retainedChunks=" + RETAINED.size()
                        + " approxRetained=" + MemoryInspector.formatBytes(retainedBytes));
                MemoryInspector.printMemorySummary();
                System.out.println();
            }
        }

        if (config.sleepSeconds > 0) {
            System.out.println("Sleeping " + config.sleepSeconds + "s for tool attachment.");
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
                    Math.max(1, CliArgs.getInt(args, "--mb", 64)),
                    Math.max(1, CliArgs.getInt(args, "--chunkMb", 1)),
                    Math.max(1, CliArgs.getInt(args, "--reportEvery", 8)),
                    Math.max(0, CliArgs.getInt(args, "--sleepSeconds", 120))
            );
        }
    }
}
