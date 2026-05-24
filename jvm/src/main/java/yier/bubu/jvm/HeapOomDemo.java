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

        int chunks = Math.max(1, config.totalMb / config.chunkMb);
        int chunkBytes = config.chunkMb * 1024 * 1024;

        System.out.println("[HeapOomDemo]");
        System.out.println("Allocating heap byte arrays and holding references.");
        System.out.println("totalMb=" + config.totalMb + " chunkMb=" + config.chunkMb + " chunks=" + chunks);
        System.out.println("Tip: run with -Xms64m -Xmx64m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=target/jvm-lab-heap.hprof");
        System.out.println();

        List<byte[]> retained = new ArrayList<byte[]>(chunks);
        for (int i = 0; i < chunks; i++) {
            byte[] block = new byte[chunkBytes];
            block[0] = (byte) i;
            retained.add(block);

            if ((i + 1) % config.reportEvery == 0 || i == chunks - 1) {
                System.out.println("allocatedChunks=" + (i + 1)
                        + " approxAllocated=" + MemoryInspector.formatBytes((long) (i + 1) * chunkBytes));
                MemoryInspector.printMemorySummary();
                System.out.println();
            }
        }

        System.out.println("Holding references to prevent GC from freeing heap arrays. retained.size=" + retained.size());
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
